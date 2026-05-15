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

class WhisperEngine(
    private val context: Context
) {

    private val ortEnv =
        OrtEnvironment.getEnvironment()

    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    private lateinit var vocabulary: Map<Int, String>
    private lateinit var reverseVocabulary: Map<String, Int>

    private lateinit var melFilters: Array<FloatArray>

    private var numLayers = 8
    private var dModel = 384
    fun load(
        onProgress: (String) -> Unit
    ): Boolean {

        return try {

            onProgress("Vocabulary 로딩 중...")
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

            onProgress("Encoder 로딩 중...")

            encoderSession =
                ortEnv.createSession(
                    loadAsset("base_encoder_11.onnx"),
                    options
                )

            onProgress("Decoder 로딩 중...")

            decoderSession =
                ortEnv.createSession(
                    loadAsset("base_decoder_11.onnx"),
                    options
                )
            numLayers = 12 //8, 12, 24, 48
            dModel = 512 // 384, 512, 768, 1024
            true

        } catch (e: Exception) {

            e.printStackTrace()
            false
        }
    }

    fun transcribeWavFile(
        path: String
    ): Pair<String, List<SpeechSegment>> {

        val audio =
            loadWav16kMono(path)

        val mel =
            AudioProcessor.logMelSpectrogram(
                audio,
                melFilters
            )

        val allSegments =
            mutableListOf<SpeechSegment>()

        val fullText =
            StringBuilder()

        var segmentId = 0

        var seekFrame = 0

        val totalFrames =
            mel[0].size

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
                    timeOffset,
                    segmentId
                )

            val text =
                result.first

            val segments =
                result.second

            val nextSeek =
                result.third

            if (text.isNotBlank()) {

                fullText
                    .append(text)
                    .append(" ")

                allSegments.addAll(segments)

                segmentId +=
                    segments.size
            }

            audioFeatures.close()

            seekFrame +=
                if (nextSeek > 0)
                    nextSeek
                else
                    WhisperConfig.N_FRAMES
        }

        return Pair(
            fullText.toString().trim(),
            allSegments
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

    /**
     * INPUT:
     * [1,80,3000]
     *
     * OUTPUT:
     * [1,1500,384]
     */
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
                mapOf(
                    "mel" to tensor
                )
            )

        tensor.close()

        return outputs[0] as OnnxTensor
    }

    /**
     * tiny:
     * [8, 1, seq, 384]
     */
    private fun createKvCache(
        seqLen: Int
    ): OnnxTensor {

        val shape = longArrayOf(numLayers.toLong(), 1, seqLen.toLong(), dModel.toLong())
        val size = numLayers * 1 * seqLen * dModel

        return OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(
                FloatArray(size)
            ),
            shape
        )
    }

    private fun runDecoder(
        audioFeatures: OnnxTensor,
        timeOffset: Float,
        startId: Int
    ): Triple<String, List<SpeechSegment>, Int> {

        val currentTokens =
            mutableListOf(
                WhisperConfig.SOT,
                WhisperConfig.KO_TOKEN,
                WhisperConfig.TRANSCRIBE
            )

        val transcriptBuilder =
            StringBuilder()

        val segments =
            mutableListOf<SpeechSegment>()

        var currentSegmentText =
            StringBuilder()

        var currentSegmentStart =
            timeOffset

        var lastTimestampToken =
            -1

        var segmentId =
            startId

        /**
         * IMPORTANT:
         *
         * 이 decoder export는:
         *
         * tokens length == kv seq_len
         *
         * 을 기대함
         *
         * 그리고 offset은 항상 0
         */

        for (step in 0 until 224) {

            val tokenArray =
                currentTokens
                    .map { it.toLong() }
                    .toLongArray()

            val tokensTensor =
                OnnxTensor.createTensor(
                    ortEnv,
                    LongBuffer.wrap(tokenArray),
                    longArrayOf(
                        1,
                        tokenArray.size.toLong()
                    )
                )

            val kvCache =
                createKvCache(
                    currentTokens.size
                )

            val offsetTensor =
                OnnxTensor.createTensor(
                    ortEnv,
                    LongBuffer.wrap(
                        longArrayOf(0)
                    ),
                    longArrayOf(1)
                )

            val outputs =
                decoderSession!!.run(
                    mapOf(
                        "tokens" to tokensTensor,
                        "audio_features" to audioFeatures,
                        "kv_cache" to kvCache,
                        "offset" to offsetTensor
                    )
                )

            /**
             * logits:
             * [1, seq, vocab]
             */

            val logits =
                (outputs[0].value as Array<Array<FloatArray>>)[0].last()

            /**
             * greedy decode
             */

            var nextToken = 0
            var maxLogit = -Float.MAX_VALUE

            for (i in logits.indices) {

                if (logits[i] > maxLogit) {

                    maxLogit =
                        logits[i]

                    nextToken =
                        i
                }
            }

            tokensTensor.close()
            kvCache.close()
            offsetTensor.close()
            outputs.close()

            /**
             * end token
             */

            if (nextToken ==
                WhisperConfig.EOT
            ) {
                break
            }

            currentTokens.add(nextToken)

            /**
             * timestamp token
             */

            if (nextToken >=
                WhisperConfig.TIMESTAMP_BEGIN
            ) {

                val relativeTime =
                    (nextToken -
                            WhisperConfig.TIMESTAMP_BEGIN) * 0.02f

                val absoluteTime =
                    timeOffset + relativeTime

                if (currentSegmentText.isNotBlank()) {

                    val cleanText =
                        currentSegmentText
                            .toString()
                            .replace("Ġ", " ")
                            .trim()

                    segments.add(
                        SpeechSegment(
                            id = segmentId++,
                            start = currentSegmentStart,
                            end = absoluteTime,
                            text = cleanText
                        )
                    )

                    transcriptBuilder
                        .append(cleanText)
                        .append(" ")

                    currentSegmentText.clear()
                }

                currentSegmentStart =
                    absoluteTime

                lastTimestampToken =
                    nextToken
            }
            else {

                val piece =
                    vocabulary[nextToken]
                        ?: ""

                currentSegmentText
                    .append(piece)
            }
        }

        /**
         * 마지막 text flush
         */

        if (currentSegmentText.isNotBlank()) {
            Log.d("currentSegmentText",currentSegmentText.toString())
            // 1. 먼저 Ġ를 공백으로 바꿉니다.
            val rawText = currentSegmentText.toString().replace("Ġ", " ")

            // 2. 위에서 만든 decodeBpeString 함수를 사용해 한글로 변환합니다.
            val cleanText = decodeBpeString(rawText).trim()

            Log.d("WhisperResult", "변환된 한글: $cleanText")
            val endTime =
                if (lastTimestampToken >=
                    WhisperConfig.TIMESTAMP_BEGIN
                ) {

                    timeOffset +
                            (lastTimestampToken -
                                    WhisperConfig.TIMESTAMP_BEGIN) * 0.02f
                }
                else {

                    timeOffset + 30f
                }

            segments.add(
                SpeechSegment(
                    id = segmentId,
                    start = currentSegmentStart,
                    end = endTime,
                    text = cleanText
                )
            )

            transcriptBuilder
                .append(cleanText)
        }

        /**
         * whisper:
         * input_stride = 2
         */

        val nextSeekFrames =
            if (lastTimestampToken >=
                WhisperConfig.TIMESTAMP_BEGIN
            ) {

                (lastTimestampToken -
                        WhisperConfig.TIMESTAMP_BEGIN) * 2
            }
            else {

                WhisperConfig.N_FRAMES
            }

        return Triple(
            transcriptBuilder.toString().trim(),
            segments,
            nextSeekFrames
        )
    }

    private fun decodeBpeString(bpeString: String): String {
        val byteMap = mutableMapOf<Char, Byte>()
        var n = 0

        // OpenAI Whisper/GPT-2 표준 byte_to_unicode 매핑 복구
        for (b in 0..255) {
            // 이 범위들은 유니코드에서 특수 기호가 아닌 일반 문자로 취급되는 구간입니다.
            if (b in 33..126 || b in 161..172 || b in 174..255) {
                byteMap[b.toChar()] = b.toByte()
            } else {
                // 그 외의 제어 문자나 공백 구간은 256번 이후의 유니코드로 매핑되어 있습니다.
                byteMap[(256 + n).toChar()] = b.toByte()
                n++
            }
        }

        val bytes = ByteArray(bpeString.length)
        for (i in bpeString.indices) {
            // 매핑 테이블에서 찾고, 없으면 fallback으로 처리 (대부분 테이블 안에 있습니다)
            bytes[i] = byteMap[bpeString[i]] ?: bpeString[i].code.toByte()
        }

        return String(bytes, Charsets.UTF_8)
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
                    ).order(ByteOrder.LITTLE_ENDIAN)

                for (i in 0 until WhisperConfig.N_MELS) {

                    for (j in 0 until 201) {

                        filters[i][j] =
                            buffer.float
                    }
                }
            }

        melFilters =
            filters
    }



    private fun loadWav16kMono(
        path: String
    ): FloatArray {

        val file =
            File(path)

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
                ).order(ByteOrder.LITTLE_ENDIAN).int

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
                    ).order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()

                for (i in 0 until pcmLen) {

                    result[i] =
                        pcm.get(i)
                            .toFloat() / 32768f
                }

                return result
            }

            offset +=
                8 + chunkSize
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