package com.speechcoach.ui.report

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.View
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

        val json = intent.getStringExtra(EXTRA_REPORT_JSON) ?: run { finish(); return }
        report = PresentationReport.fromJson(json)

        renderSummary()
        renderScript()
        renderWpmChart()
        renderVolumeChart()
        renderFillerList()
        renderTremorSections()
    }

    private fun renderSummary() {
        val min = report.durationSec / 60
        val sec = report.durationSec % 60
        binding.tvScore.text = "${report.overallScore}점"
        binding.tvDuration.text = "발표 시간: ${min}분 ${sec}초"
        binding.tvFeedback.text = report.aiFeedback
        binding.tvSpeedSummary.text = "평균 ${report.speedAnalysis.avgWpm} WPM | 최고 ${report.speedAnalysis.maxWpm}"
        binding.tvFillerSummary.text = "습관어 총 ${report.fillerAnalysis.totalFillers}회 (${String.format("%.1f", report.fillerAnalysis.fillerRatePercent)}%)"
        binding.tvVoiceSummary.text = "자신감 ${report.voiceAnalysis.avgConfidencePercent}% | 떨림 ${report.voiceAnalysis.avgTremorPercent}%"
    }

    private fun renderScript() {
        val transcript = report.fullTranscript
        if (transcript.isBlank()) {
            binding.tvScript.text = "(인식된 텍스트가 없습니다)"
            return
        }

        val ssb = SpannableStringBuilder(transcript)
        val analyzer = FillerWordAnalyzer()

        analyzer.highlightPatterns().forEach { (_, regex) ->
            regex.findAll(transcript).forEach { match ->
                val start = match.range.first
                val end = match.range.last + 1
                ssb.setSpan(ForegroundColorSpan(Color.parseColor("#E53935")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(BackgroundColorSpan(Color.parseColor("#33E53935")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        binding.tvScript.text = ssb
    }

    private fun renderWpmChart() {
        val chart = binding.chartWpm
        val entries = report.speedAnalysis.wpmHistory.map { Entry(it[0].toFloat(), it[1].toFloat()) }
        if (entries.isEmpty()) { chart.visibility = View.GONE; return }

        val dataSet = LineDataSet(entries, "WPM")
        dataSet.color = Color.parseColor("#4CAF50")
        dataSet.lineWidth = 2f
        dataSet.setDrawCircles(false)
        dataSet.setDrawValues(false)
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        dataSet.setDrawFilled(true)
        dataSet.fillColor = Color.parseColor("#804CAF50")

        val limitLine = LimitLine(200f, "빠름")
        limitLine.lineColor = Color.parseColor("#FF9800")
        limitLine.textColor = Color.parseColor("#FF9800")

        chart.data = LineData(dataSet)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.valueFormatter = SecondsFormatter()
        xAxis.gridColor = Color.LTGRAY

        val yAxis = chart.axisLeft
        yAxis.axisMinimum = 0f
        yAxis.axisMaximum = 300f
        yAxis.addLimitLine(limitLine)

        chart.axisRight.isEnabled = false
        chart.animateX(600)
        chart.invalidate()
    }

    private fun renderVolumeChart() {
        val chart = binding.chartVolume
        val startMs = report.voiceAnalysis.volumeHistory.firstOrNull()?.get(0) ?: 0.0
        val entries = report.voiceAnalysis.volumeHistory.map {
            Entry(((it[0] - startMs) / 1000f).toFloat(), it[1].toFloat())
        }
        if (entries.isEmpty()) { chart.visibility = View.GONE; return }

        val dataSet = LineDataSet(entries, "볼륨(dB)")
        dataSet.color = Color.parseColor("#2196F3")
        dataSet.setDrawCircles(false)
        dataSet.setDrawValues(false)

        chart.data = LineData(dataSet)
        chart.description.isEnabled = false

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.valueFormatter = SecondsFormatter()

        val yAxis = chart.axisLeft
        yAxis.axisMinimum = -70f
        yAxis.axisMaximum = 0f

        chart.axisRight.isEnabled = false
        chart.animateX(600)
        chart.invalidate()
    }

    private fun renderFillerList() {
        val sb = StringBuilder()
        report.fillerAnalysis.fillerBreakdown
            .entries.sortedByDescending { it.value.count }
            .forEach { (word, detail) ->
                val ts = detail.timestamps.take(3).joinToString(", ") {
                    "${(it / 60).toInt()}:${String.format("%02d", (it % 60).toInt())}"
                }
                val more = if (detail.timestamps.size > 3) " 외 ${detail.timestamps.size - 3}건" else ""
                sb.appendLine("• \"$word\" — ${detail.count}회 ($ts$more)")
            }
        binding.tvFillerList.text = if (sb.isEmpty()) "감지된 습관어 없음 👍" else sb.toString().trim()
    }

    private fun renderTremorSections() {
        val sections = report.voiceAnalysis.tremorSections
        if (sections.isEmpty()) {
            binding.tvTremorSections.text = "떨림 위험 구간 없음 👍"; return
        }
        binding.tvTremorSections.text = sections.joinToString("\n") { s ->
            "⚠️ ${(s.startSec / 60).toInt()}:${String.format("%02d", (s.startSec % 60).toInt())} ~ 강도 ${s.intensity}%"
        }
    }

    private class SecondsFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val t = value.toInt()
            return "${t / 60}:${String.format("%02d", t % 60)}"
        }
    }
}