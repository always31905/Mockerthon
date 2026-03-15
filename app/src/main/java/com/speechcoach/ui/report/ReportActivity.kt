package com.speechcoach.ui.report

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.speechcoach.databinding.ActivityReportBinding
import com.speechcoach.model.PresentationReport

/**
 * ReportActivity (Track 2 - 발표 종료 후 포스트 리포트)
 *
 * PresentationReport JSON을 받아서:
 * 1. 종합 점수 + AI 피드백 텍스트 표시
 * 2. WPM 그래프 (MPAndroidChart LineChart)
 * 3. 볼륨 그래프
 * 4. 습관어 빈도 목록
 * 5. 떨림 위험 구간 타임라인
 */
class ReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REPORT_JSON = "extra_report_json"
    }

    private lateinit var binding: ActivityReportBinding
    private lateinit var report: PresentationReport

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val json = intent.getStringExtra(EXTRA_REPORT_JSON)
            ?: run { finish(); return }

        report = PresentationReport.fromJson(json)

        renderSummary()
        renderWpmChart()
        renderVolumeChart()
        renderFillerList()
        renderTremorSections()
    }

    // ── 1. 종합 요약 ──────────────────────────────────────────────
    private fun renderSummary() {
        val durationMin = report.durationSec / 60
        val durationSec = report.durationSec % 60

        binding.tvScore.text = "${report.overallScore}점"
        binding.tvDuration.text = "발표 시간: ${durationMin}분 ${durationSec}초"
        binding.tvFeedback.text = report.aiFeedback

        val speed = report.speedAnalysis
        binding.tvSpeedSummary.text =
            "평균 ${speed.avgWpm} WPM  |  최고 ${speed.maxWpm}  |  최저 ${speed.minWpm}"

        val filler = report.fillerAnalysis
        binding.tvFillerSummary.text =
            "습관어 총 ${filler.totalFillers}회 " +
            "(전체 발화의 ${String.format("%.1f", filler.fillerRatePercent)}%)"

        val voice = report.voiceAnalysis
        binding.tvVoiceSummary.text =
            "자신감 ${voice.avgConfidencePercent}%  |  떨림 ${voice.avgTremorPercent}%"
    }

    // ── 2. WPM 라인 차트 ─────────────────────────────────────────
    private fun renderWpmChart() {
        val chart = binding.chartWpm
        val entries = report.speedAnalysis.wpmHistory.mapIndexed { i, pair ->
            Entry(pair[0].toFloat(), pair[1].toFloat())
        }
        if (entries.isEmpty()) { chart.visibility = android.view.View.GONE; return }

        val dataSet = LineDataSet(entries, "WPM").apply {
            color          = Color.parseColor("#4CAF50")
            lineWidth      = 2f
            circleRadius   = 1f
            setDrawCircles(false)
            setDrawValues(false)
            mode           = LineDataSet.Mode.CUBIC_BEZIER
            // 빠름 기준선 강조를 위해 그라데이션 채우기
            setDrawFilled(true)
            fillColor      = Color.parseColor("#804CAF50")
        }

        // 빠름 기준선 (200 WPM)
        val limitLine = com.github.mikephil.charting.components.LimitLine(200f, "빠름 기준").apply {
            lineColor    = Color.parseColor("#FF9800")
            lineWidth    = 1.5f
            textColor    = Color.parseColor("#FF9800")
            textSize     = 10f
            labelPosition = com.github.mikephil.charting.components.LimitLine.LimitLabelPosition.RIGHT_TOP
        }

        chart.apply {
            data         = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled      = false
            xAxis.apply {
                position          = XAxis.XAxisPosition.BOTTOM
                valueFormatter    = SecondsFormatter()
                granularity       = 10f
                gridColor         = Color.LTGRAY
            }
            axisLeft.apply {
                axisMinimum   = 0f
                axisMaximum   = 300f
                addLimitLine(limitLine)
                gridColor     = Color.LTGRAY
            }
            axisRight.isEnabled = false
            setTouchEnabled(true)
            setPinchZoom(true)
            animateX(600)
            invalidate()
        }
    }

    // ── 3. 볼륨 라인 차트 ─────────────────────────────────────────
    private fun renderVolumeChart() {
        val chart = binding.chartVolume
        val startMs = report.voiceAnalysis.volumeHistory.firstOrNull()?.get(0) ?: 0.0
        val entries = report.voiceAnalysis.volumeHistory.mapIndexed { i, pair ->
            Entry(((pair[0] - startMs) / 1000f).toFloat(), pair[1].toFloat())
        }
        if (entries.isEmpty()) { chart.visibility = android.view.View.GONE; return }

        val dataSet = LineDataSet(entries, "볼륨(dB)").apply {
            color          = Color.parseColor("#2196F3")
            lineWidth      = 1.5f
            circleRadius   = 1f
            setDrawCircles(false)
            setDrawValues(false)
            mode           = LineDataSet.Mode.LINEAR
        }

        chart.apply {
            data         = LineData(dataSet)
            description.isEnabled = false
            xAxis.apply {
                position       = XAxis.XAxisPosition.BOTTOM
                valueFormatter = SecondsFormatter()
            }
            axisLeft.apply {
                axisMinimum = -70f
                axisMaximum = 0f
            }
            axisRight.isEnabled = false
            animateX(600)
            invalidate()
        }
    }

    // ── 4. 습관어 목록 ────────────────────────────────────────────
    private fun renderFillerList() {
        val sb = StringBuilder()
        report.fillerAnalysis.fillerBreakdown
            .entries
            .sortedByDescending { it.value.count }
            .forEach { (word, detail) ->
                val timestamps = detail.timestamps.take(3).joinToString(", ") {
                    val min = (it / 60).toInt()
                    val sec = (it % 60).toInt()
                    "${min}:${String.format("%02d", sec)}"
                }
                val more = if (detail.timestamps.size > 3) " 외 ${detail.timestamps.size - 3}건" else ""
                sb.appendLine("• \"$word\" — ${detail.count}회   ($timestamps$more)")
            }
        binding.tvFillerList.text = if (sb.isEmpty()) "감지된 습관어 없음 👍" else sb.toString().trim()
    }

    // ── 5. 떨림 위험 구간 타임라인 ────────────────────────────────
    private fun renderTremorSections() {
        val sections = report.voiceAnalysis.tremorSections
        if (sections.isEmpty()) {
            binding.tvTremorSections.text = "떨림 위험 구간 없음 👍"
            return
        }
        val sb = StringBuilder()
        sections.forEach { s ->
            val startMin = (s.startSec / 60).toInt()
            val startSec = (s.startSec % 60).toInt()
            val endMin   = (s.endSec / 60).toInt()
            val endSec   = (s.endSec % 60).toInt()
            sb.appendLine("⚠️ ${startMin}:${String.format("%02d", startSec)} ~ " +
                    "${endMin}:${String.format("%02d", endSec)}  (강도 ${s.intensity}%)")
        }
        binding.tvTremorSections.text = sb.toString().trim()
    }

    // ── X축 포매터 (초 → "mm:ss") ────────────────────────────────
    private class SecondsFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val totalSec = value.toInt()
            return "${totalSec / 60}:${String.format("%02d", totalSec % 60)}"
        }
    }
}
