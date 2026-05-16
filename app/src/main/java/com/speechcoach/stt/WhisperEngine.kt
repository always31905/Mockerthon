package com.speechcoach.stt

import ai.onnxruntime.*
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max

data class SpeechWord(
    val word: String,
    val start: Float,
    val end: Float
)

class WhisperEngine(
    private val context: Context
) {

    companion object {
        private const val SAMPLE_RATE = 16000

        /**
         * Whisper timestamp tokens
         * <|0.00|> = 50364
         */
        private const val TIMESTAMP_BEGIN = 50364

        /**
         * Whisper timestamp precision
         * 1 token = 0.02 sec
         */
        private const val TIME_PRECISION = 0.02f
    }

    private val ortEnv = OrtEnvironment.getEnvironment()

    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    private lateinit var vocabulary: Map<Int, String>
    private lateinit var reverseVocabulary: Map<String, Int>

    private lateinit var melFilters: Array<FloatArray>

    private var numLayers = 12
    private var dModel = 512

    private var modelSize = "base"
    private var modelQuant = "int8"

    fun load(
        onProgress: (String) -> Unit
    ): Boolean {

        return try {

            onProgress("Whisper 모델 로딩 중...")

            loadVocabularyAndFilters()

            val options =
                OrtSession.SessionOptions().apply {

                    addConfigEntry(
                        "session.intra_op.num_threads",
                        "4"
                    )

                    setOptimizationLevel(
                        OrtSession.SessionOptions.OptLevel.ALL_OPT
                    )
                }

            encoderSession =
                ortEnv.createSession(
                    loadAsset("${modelSize}_encoder_11_${modelQuant}.onnx"),
                    options
                )

            decoderSession =
                ortEnv.createSession(
                    loadAsset("${modelSize}_decoder_11_${modelQuant}.onnx"),
                    options
                )

            when (modelSize) {

                "tiny" -> {
                    numLayers = 8
                    dModel = 384
                }

                "base" -> {
                    numLayers = 12
                    dModel = 512
                }

                "small" -> {
                    numLayers = 24
                    dModel = 768
                }

                "medium" -> {
                    numLayers = 48
                    dModel = 1024
                }
            }

            true

        } catch (e: Exception) {

            e.printStackTrace()
            false
        }
    }

    fun transcribeWavFile(
        path: String
    ): Pair<String, List<SpeechWord>> {

        val audio = loadWav16kMono(path)

        val mel =
            AudioProcessor.logMelSpectrogram(
                audio,
                melFilters
            )

        val allWords =
            mutableListOf<SpeechWord>()

        val fullText =
            StringBuilder()

        var seekFrame = 0

        val totalFrames = mel[0].size

        while (seekFrame < totalFrames) {

            val melChunk =
                extractMelChunk(
                    mel,
                    seekFrame,
                    WhisperConfig.N_FRAMES
                )

            val audioFeatures =
                runEncoder(melChunk)

            val timeOffset =
                seekFrame * 0.02f

            val result =
                runDecoder(
                    audioFeatures,
                    timeOffset
                )

            val text = result.first
            val words = result.second

            if (text.isNotBlank()) {

                fullText
                    .append(text)
                    .append(" ")

                allWords.addAll(words)
            }

            audioFeatures.close()

            seekFrame += WhisperConfig.N_FRAMES
        }

        return Pair(
            fullText.toString().trim(),
            allWords
        )
    }

    private fun extractMelChunk(
        fullMel: Array<FloatArray>,
        startFrame: Int,
        size: Int
    ): Array<FloatArray> {

        val chunk =
            Array(WhisperConfig.N_MELS) {
                FloatArray(size)
            }

        val maxFrame =
            fullMel[0].size

        for (i in 0 until WhisperConfig.N_MELS) {

            for (j in 0 until size) {

                chunk[i][j] =
                    if (startFrame + j < maxFrame)
                        fullMel[i][startFrame + j]
                    else
                        0f
            }
        }

        return chunk
    }

    private fun runEncoder(
        mel: Array<FloatArray>
    ): OnnxTensor {

        val flat =
            FloatArray(
                WhisperConfig.N_MELS *
                        WhisperConfig.N_FRAMES
            )

        for (i in 0 until WhisperConfig.N_MELS) {

            System.arraycopy(
                mel[i],
                0,
                flat,
                i * WhisperConfig.N_FRAMES,
                WhisperConfig.N_FRAMES
            )
        }

        val tensor =
            OnnxTensor.createTensor(
                ortEnv,
                FloatBuffer.wrap(flat),
                longArrayOf(
                    1,
                    WhisperConfig.N_MELS.toLong(),
                    WhisperConfig.N_FRAMES.toLong()
                )
            )

        val outputs =
            encoderSession!!.run(
                mapOf("mel" to tensor)
            )

        tensor.close()

        return outputs[0] as OnnxTensor
    }

    private fun createKvCache(
        seqLen: Int
    ): OnnxTensor {

        val shape =
            longArrayOf(
                numLayers.toLong(),
                1,
                seqLen.toLong(),
                dModel.toLong()
            )

        val size =
            numLayers *
                    seqLen *
                    dModel

        return OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(FloatArray(size)),
            shape
        )
    }

    private fun runDecoder(
        audioFeatures: OnnxTensor,
        timeOffset: Float
    ): Pair<String, List<SpeechWord>> {

        val tokens =
            mutableListOf(
                WhisperConfig.SOT,
                WhisperConfig.KO_TOKEN,
                WhisperConfig.TRANSCRIBE
            )

        val finalWords = mutableListOf<SpeechWord>()
        val transcript = StringBuilder()

        var segmentStartTime = timeOffset
        val currentSegmentWords = mutableListOf<String>()
        val currentWordBuffer = StringBuilder()

        // 🚀 단어(String) 반복 감지를 위한 변수
        var lastAddedWord = ""
        var wordRepeatCount = 0
        var isHallucinating = false

        // 문장(Segment) 단위로 묶인 단어들의 시간을 배분하는 내부 함수
        fun flushSegment(segmentEndTime: Float) {
            // 1. 버퍼에 남아있는 단어 처리
            val wordText = decodeBpeString(
                currentWordBuffer.toString().replace("Ġ", " ")
            ).trim()

            if (wordText.isNotBlank()) {
                currentSegmentWords.add(wordText)
                currentWordBuffer.clear()
            }

            if (currentSegmentWords.isEmpty()) {
                segmentStartTime = segmentEndTime
                return
            }

            // 2. 글자 수 기반 시간 보간(Interpolation) 연산
            val segmentDuration = max(0f, segmentEndTime - segmentStartTime)
            val totalChars = currentSegmentWords.sumOf { it.length }

            var currentTime = segmentStartTime

            for (wordStr in currentSegmentWords) {
                // 🚀 단어 반복 감지 로직
                if (wordStr == lastAddedWord) {
                    wordRepeatCount++
                    if (wordRepeatCount >= 4) { // 같은 단어가 4번 이상 반복되면
                        Log.w("WhisperEngine", "단어 반복(환각) 감지됨: [$wordStr]. 디코딩을 강제 종료합니다.")
                        isHallucinating = true
                        break // 단어 배분 중단
                    }
                } else {
                    wordRepeatCount = 0
                }
                lastAddedWord = wordStr

                // 단어 길이에 비례한 가중치 계산 (글자가 아예 없으면 동일 배분)
                val ratio = if (totalChars > 0) {
                    wordStr.length.toFloat() / totalChars
                } else {
                    1f / currentSegmentWords.size
                }

                val wordDuration = segmentDuration * ratio

                finalWords.add(
                    SpeechWord(
                        word = wordStr,
                        start = currentTime,
                        end = currentTime + wordDuration
                    )
                )

                transcript.append(wordStr).append(" ")
                currentTime += wordDuration
            }

            // 다음 세그먼트를 위해 초기화
            currentSegmentWords.clear()
            segmentStartTime = segmentEndTime
        }

        var repeatCount = 0
        var lastToken = -1

        for (step in 0 until 224) {

            val tokenArray = tokens.map { it.toLong() }.toLongArray()

            val tokensTensor = OnnxTensor.createTensor(
                ortEnv,
                LongBuffer.wrap(tokenArray),
                longArrayOf(1, tokenArray.size.toLong())
            )

            val kvCache = createKvCache(tokens.size)

            val offsetTensor = OnnxTensor.createTensor(
                ortEnv,
                LongBuffer.wrap(longArrayOf(0)),
                longArrayOf(1)
            )

            val outputs = decoderSession!!.run(
                mapOf(
                    "tokens" to tokensTensor,
                    "audio_features" to audioFeatures,
                    "kv_cache" to kvCache,
                    "offset" to offsetTensor
                )
            )

            val logits = (outputs[0].value as Array<Array<FloatArray>>)[0].last()

            logits[50363] = -Float.MAX_VALUE

            var nextToken = 0
            var maxLogit = -Float.MAX_VALUE

            for (i in logits.indices) {
                if (logits[i] > maxLogit) {
                    maxLogit = logits[i]
                    nextToken = i
                }
            }

            tokensTensor.close()
            kvCache.close()
            offsetTensor.close()
            outputs.close()

            /**
             * end token
             */
            if (nextToken == WhisperConfig.EOT) {
                break
            }

            // 🚀 (기존) 토큰 단위 방어 로직: 같은 의미 없는 토큰이 4번 이상 연속 나오면 강제 종료
            if (nextToken == lastToken && nextToken < TIMESTAMP_BEGIN) {
                repeatCount++
                if (repeatCount >= 4) {
                    Log.w("WhisperEngine", "토큰 루프 감지됨. 디코딩 강제 종료.")
                    break
                }
            } else {
                repeatCount = 0
            }
            lastToken = nextToken

            tokens.add(nextToken)

            /**
             * timestamp token (타임스탬프를 만나면 지금까지 모인 단어들의 시간을 배분)
             */
            if (nextToken >= TIMESTAMP_BEGIN) {
                val timestamp = timeOffset + ((nextToken - TIMESTAMP_BEGIN) * TIME_PRECISION)
                flushSegment(timestamp)

                // 🚀 단어 루프(환각)가 감지되었다면 메인 추론 루프(224)도 즉시 탈출
                if (isHallucinating) {
                    break
                }
                continue
            }

            val piece = vocabulary[nextToken] ?: ""

            /**
             * whitespace token (띄어쓰기를 만나면 단어 분리하여 세그먼트 배열에 저장)
             */
            if (piece.startsWith("Ġ")) {
                val wordText = decodeBpeString(
                    currentWordBuffer.toString().replace("Ġ", " ")
                ).trim()

                if (wordText.isNotBlank()) {
                    currentSegmentWords.add(wordText)
                    currentWordBuffer.clear()
                }
            }

            currentWordBuffer.append(piece)
        }

        /**
         * EOT 토큰 이후에 버퍼에 남아있는 마지막 단어들 처리 (강제 플러시)
         * 단, 환각으로 인해 강제 종료된 상태가 아닐 때만 처리합니다.
         */
        if (!isHallucinating) {
            val leftoverText = decodeBpeString(
                currentWordBuffer.toString().replace("Ġ", " ")
            ).trim()

            if (leftoverText.isNotBlank()) {
                currentSegmentWords.add(leftoverText)
                currentWordBuffer.clear()
            }

            if (currentSegmentWords.isNotEmpty()) {
                val totalChars = currentSegmentWords.sumOf { it.length }
                val estimatedDuration = totalChars * 0.1f // 1글자당 약 0.1초로 추정
                flushSegment(segmentStartTime + estimatedDuration)
            }
        }

        for( i in 0 until finalWords.size){
            Log.d("WhisperEngine", "Word: ${finalWords[i].word}, Start: ${finalWords[i].start}, End: ${finalWords[i].end}")
        }

        return Pair(
            transcript.toString().trim(),
            finalWords
        )
    }

    private fun decodeBpeString(
        bpeString: String
    ): String {

        val byteMap =
            mutableMapOf<Char, Byte>()

        var n = 0

        for (b in 0..255) {

            if (
                b in 33..126 ||
                b in 161..172 ||
                b in 174..255
            ) {

                byteMap[b.toChar()] =
                    b.toByte()

            } else {

                byteMap[(256 + n).toChar()] =
                    b.toByte()

                n++
            }
        }

        val bytes =
            ByteArray(bpeString.length)

        for (i in bpeString.indices) {

            bytes[i] =
                byteMap[bpeString[i]]
                    ?: bpeString[i].code.toByte()
        }

        return String(
            bytes,
            Charsets.UTF_8
        )
    }

    private fun loadVocabularyAndFilters() {

        val json =
            context.assets
                .open("vocab.json")
                .bufferedReader()
                .use { it.readText() }

        val jsonObject =
            JSONObject(json)

        val vocab =
            mutableMapOf<Int, String>()

        val reverse =
            mutableMapOf<String, Int>()

        for (key in jsonObject.keys()) {

            val id =
                jsonObject.getInt(key)

            vocab[id] = key
            reverse[key] = id
        }

        vocabulary = vocab
        reverseVocabulary = reverse

        val filters =
            Array(WhisperConfig.N_MELS) {
                FloatArray(201)
            }

        context.assets
            .open("mel_filters.bin")
            .use { input ->

                val buffer =
                    ByteBuffer.wrap(
                        input.readBytes()
                    ).order(
                        ByteOrder.LITTLE_ENDIAN
                    )

                for (i in 0 until WhisperConfig.N_MELS) {

                    for (j in 0 until 201) {

                        filters[i][j] =
                            buffer.float
                    }
                }
            }

        melFilters = filters
    }

    private fun loadWav16kMono(
        path: String
    ): FloatArray {

        val file = File(path)

        val bytes =
            file.readBytes()

        var offset = 12

        while (offset < bytes.size - 8) {

            val chunkId =
                String(bytes, offset, 4)

            val chunkSize =
                ByteBuffer.wrap(
                    bytes,
                    offset + 4,
                    4
                ).order(
                    ByteOrder.LITTLE_ENDIAN
                ).int

            if (chunkId == "data") {

                val pcmLen =
                    chunkSize / 2

                val result =
                    FloatArray(pcmLen)

                val pcm =
                    ByteBuffer.wrap(
                        bytes,
                        offset + 8,
                        chunkSize
                    ).order(
                        ByteOrder.LITTLE_ENDIAN
                    ).asShortBuffer()

                for (i in 0 until pcmLen) {

                    result[i] =
                        pcm.get(i)
                            .toFloat() / 32768f
                }

                return result
            }

            offset += (8 + chunkSize)
        }

        throw IllegalArgumentException(
            "WAV data chunk not found"
        )
    }

    private fun loadAsset(
        name: String
    ): ByteArray {

        return context.assets
            .open(name)
            .readBytes()
    }

    fun release() {

        encoderSession?.close()
        decoderSession?.close()

        ortEnv.close()
    }
}