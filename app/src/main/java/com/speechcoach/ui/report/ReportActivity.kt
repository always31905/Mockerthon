package com.speechcoach.ui.report

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.speechcoach.databinding.ActivityReportBinding
import com.speechcoach.model.PresentationReport
import com.speechcoach.stt.FillerWordAnalyzer

class ReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REPORT_JSON = "extra_report_json"
    }

    private lateinit var binding: ActivityReportBinding
    private lateinit var report:  PresentationReport

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val json = intent.getStringExtra(EXTRA_REPORT_JSON)
            ?: run { finish(); return }

        report = PresentationReport.fromJson(json)

        renderSummary()
        renderScript()          // ← 스크립트 (습관어 하이라이트)
        renderWpmChart()
        renderVolumeChart()
        renderFillerList()
        renderTremorSections()
    }

    // ── 1. 종합 요약 ──────────────────────────────────────────────
    private fun renderSummary() {
        val min = report.durationSec / 60
        val sec = report.durationSec % 60
        binding.tvScore.text       = "${report.overallScore}점"
        binding.tvDuration.text    = "발표 시간: ${min}분 ${sec}초"
        binding.tvFeedback.text    = report.aiFeedback
        binding.tvSpeedSummary.text =
            "평균 ${report.speedAnalysis.avgWpm} WPM  |  최고 ${report.speedAnalysis.maxWpm}  |  최저 ${report.speedAnalysis.minWpm}"
        binding.tvFillerSummary.text =
            "습관어 총 ${report.fillerAnalysis.totalFillers}회 " +
            "(전체 발화의 ${String.format("%.1f", report.fillerAnalysis.fillerRatePercent)}%)"
        binding.tvVoiceSummary.text =
            "자신감 ${report.voiceAnalysis.avgConfidencePercent}%  |  떨림 ${report.voiceAnalysis.avgTremorPercent}%"
    }

    // ── 2. 전체 스크립트 (습관어 빨간색 하이라이트) ────────────────
    private fun renderScript() {
        val transcript = report.fullTranscript
        if (transcript.isBlank()) {
            binding.tvScript.text = "(인식된 텍스트가 없습니다)"
            return
        }

        val ssb      = SpannableStringBuilder(transcript)
        val analyzer = FillerWordAnalyzer()

        // 모든 습관어 패턴을 순회하며 매칭 위치에 빨간색 적용
        analyzer.highlightPatterns().forEach { (_, regex) ->
            regex.findAll(transcript).forEach { match ->
                val start = match.range.first
                val end   = match.range.last + 1
                // 빨간 글자색
                ssb.setSpan(
                    ForegroundColorSpan(Color.parseColor("#E53935")),
                    start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                // 굵게
                ssb.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                // 연한 빨간 배경
                ssb.setSpan(
                    BackgroundColorSpan(Color.parseColor("#33E53935")),
                    start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        binding.tvScript.text = ssb
    }

    // ── 3. WPM 라인 차트 ─────────────────────────────────────────
    private fun renderWpmChart() {
        val chart   = binding.chartWpm
        val entries = report.speedAnalysis.wpmHistory.map { pair ->
            Entry(pair[0].toFloat(), pair[1].toFloat())
        }
        if (entries.isEmpty()) { chart.visibility = android.view.View.GONE; return }

        val dataSet = LineDataSet(entries, "WPM").apply {
            color        = Color.parseColor("#4CAF50")
            lineWidth    = 2f
            circleRadius = 1f
            setDrawCircles(false)
            setDrawValues(false)
            mode         = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor    = Color.parseColor("#804CAF50")
        }

        val limitLine = LimitLine(200f, "빠름 기준").apply {
            lineColor     = Color.parseColor("#FF9800")
            lineWidth     = 1.5f
            textColor     = Color.parseColor("#FF9800")
            textSize      = 10f
            labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
        }

        chart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled      = false
            xAxis.apply {
                position       = XAxis.XAxisPosition.BOTTOM
                valueFormatter = SecondsFormatter()
                granularity    = 10f
                gridColor      = Color.LTGRAY
            }
            axisLeft.apply {
                axisMinimum = 0f
                axisMaximum = 300f
                addLimitLine(limitLine)
                gridColor   = Color.LTGRAY
            }
            axisRight.isEnabled = false
            setTouchEnabled(true)
            setPinchZoom(true)
            animateX(600)
            invalidate()
        }
    }

    // ── 4. 볼륨 라인 차트 ─────────────────────────────────────────
    private fun renderVolumeChart() {
        val chart   = binding.chartVolume
        val startMs = report.voiceAnalysis.volumeHistory.firstOrNull()?.get(0) ?: 0.0
        val entries = report.voiceAnalysis.volumeHistory.map { pair ->
            Entry(((pair[0] - startMs) / 1000f).toFloat(), pair[1].toFloat())
        }
        if (entries.isEmpty()) { chart.visibility = android.view.View.GONE; return }

        val dataSet = LineDataSet(entries, "볼륨(dB)").apply {
            color        = Color.parseColor("#2196F3")
            lineWidth    = 1.5f
            circleRadius = 1f
            setDrawCircles(false)
            setDrawValues(false)
            mode         = LineDataSet.Mode.LINEAR
        }

        chart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            xAxis.apply {
                position       = XAxis.XAxisPosition.BOTTOM
                valueFormatter = SecondsFormatter()
            }
            axisLeft.apply { axisMinimum = -70f; axisMaximum = 0f }
            axisRight.isEnabled = false
            animateX(600)
            invalidate()
        }
    }

    // ── 5. 습관어 목록 ────────────────────────────────────────────
    private fun renderFillerList() {
        val sb = StringBuilder()
        report.fillerAnalysis.fillerBreakdown
            .entries.sortedByDescending { it.value.count }
            .forEach { (word, detail) ->
                val ts   = detail.timestamps.take(3).joinToString(", ") {
                    "${(it / 60).toInt()}:${String.format("%02d", (it % 60).toInt())}"
                }
                val more = if (detail.timestamps.size > 3) " 외 ${detail.timestamps.size - 3}건" else ""
                sb.appendLine("• \"$word\" — ${detail.count}회   ($ts$more)")
            }
        binding.tvFillerList.text =
            if (sb.isEmpty()) "감지된 습관어 없음 👍" else sb.toString().trim()
    }

    // ── 6. 떨림 위험 구간 ─────────────────────────────────────────
    private fun renderTremorSections() {
        val sections = report.voiceAnalysis.tremorSections
        if (sections.isEmpty()) {
            binding.tvTremorSections.text = "떨림 위험 구간 없음 👍"; return
        }
        binding.tvTremorSections.text = sections.joinToString("\n") { s ->
            "⚠️ ${(s.startSec / 60).toInt()}:${String.format("%02d", (s.startSec % 60).toInt())} ~ " +
            "${(s.endSec / 60).toInt()}:${String.format("%02d", (s.endSec % 60).toInt())}  (강도 ${s.intensity}%)"
        }
    }

    private class SecondsFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val t = value.toInt()
            return "${t / 60}:${String.format("%02d", t % 60)}"
        }
    }
}
