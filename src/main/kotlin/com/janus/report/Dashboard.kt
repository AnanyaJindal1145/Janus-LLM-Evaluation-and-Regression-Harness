package com.janus.report

import com.janus.compare.MetricComparison
import com.janus.compare.Verdict
import kotlin.math.abs
import kotlin.math.max

/**
 * A self-contained, minimalist HTML dashboard for a comparison — no backend, no JS, one static
 * file that opens in any browser and screenshots cleanly for the README.
 *
 * The hero visual is a per-metric DELTA PLOT that makes the two-bar rule legible: a shared
 * auto-scaled axis centered on zero, a shaded noise-floor band, and the bootstrap CI drawn as a
 * colored bar with a point marker. A regression is visible as a colored bar sitting entirely to
 * the left of zero AND entirely past the grey noise band. Colors are from a validated,
 * colorblind-safe status palette, and every verdict is paired with a text label so color never
 * carries meaning alone.
 */
object Dashboard {

    fun render(
        comparisons: List<MetricComparison>,
        title: String = "Janus — Regression Report",
        oldRunId: String = "",
        newRunId: String = "",
    ): String {
        val regressions = comparisons.count { it.verdict == Verdict.REGRESSION }
        val headlineVerdict = if (regressions > 0) "REGRESSION" else "NO REGRESSION"
        val headlineClass = if (regressions > 0) "critical" else "good"

        // Shared delta axis: scale to the data so small deltas aren't crushed near the centre.
        val bound = comparisons.flatMap { listOf(abs(it.ci.lo), abs(it.ci.hi), it.noiseFloor) }
            .maxOrNull() ?: 0.1
        val axisMax = max(bound * 1.3, 0.02)

        return buildString {
            appendLine("<!doctype html>")
            appendLine("<html lang=\"en\"><head><meta charset=\"utf-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            appendLine("<title>$title</title>")
            appendLine(style())
            appendLine("</head><body><div class=\"wrap\">")

            appendLine("<header>")
            appendLine("  <h1>$title</h1>")
            appendLine("  <p class=\"sub\">CI-for-prompts · case-level bootstrap · two-bar verdict</p>")
            appendLine("</header>")

            appendLine("<section class=\"tiles\">")
            tile("Overall", headlineVerdict, headlineClass)
            tile("Metrics", comparisons.size.toString(), "")
            tile("Regressions", regressions.toString(), if (regressions > 0) "critical" else "")
            if (oldRunId.isNotBlank()) tile("Baseline", oldRunId.take(8), "mono")
            if (newRunId.isNotBlank()) tile("Candidate", newRunId.take(8), "mono")
            appendLine("</section>")

            // axis legend (once, above the cards)
            appendLine("<div class=\"axis-legend\">")
            appendLine("  <span class=\"lg\"><span class=\"sw band\"></span> noise floor</span>")
            appendLine("  <span class=\"lg\"><span class=\"sw zero\"></span> no change (Δ = 0)</span>")
            appendLine("  <span class=\"lg\">delta axis: ±${"%.3f".format(axisMax)}</span>")
            appendLine("</div>")

            for (c in comparisons) appendCard(c, axisMax)

            appendLine("<footer>A regression requires the 95% CI entirely below zero <b>and</b> the delta beyond the measured noise floor — both bars, or it is not flagged.</footer>")
            appendLine("</div></body></html>")
        }
    }

    private fun StringBuilder.tile(label: String, value: String, cls: String) {
        appendLine("  <div class=\"tile\"><div class=\"tile-label\">$label</div>" +
            "<div class=\"tile-value $cls\">$value</div></div>")
    }

    private fun StringBuilder.appendCard(c: MetricComparison, axisMax: Double) {
        val cls = verdictClass(c.verdict)
        val label = c.verdict.name.replace('_', ' ')

        appendLine("<section class=\"card\">")
        // header line
        appendLine("  <div class=\"card-head\">")
        appendLine("    <div><span class=\"metric\">${c.metric}</span> <span class=\"n\">n=${c.n}</span></div>")
        appendLine("    <span class=\"badge $cls\"><span class=\"ind $cls\"></span> $label</span>")
        appendLine("  </div>")

        // score bars: old vs new on 0..1
        appendLine("  <div class=\"scores\">")
        scoreBar("old", c.oldMean, "muted")
        scoreBar("new", c.newMean, cls)
        appendLine("  </div>")

        // delta plot
        fun pct(v: Double) = (50.0 + (v / axisMax) * 50.0).coerceIn(0.0, 100.0)
        val zero = 50.0
        val bandLo = pct(-c.noiseFloor); val bandHi = pct(c.noiseFloor)
        val ciLo = pct(c.ci.lo); val ciHi = pct(c.ci.hi); val ptX = pct(c.ci.point)

        appendLine("  <div class=\"delta\">")
        appendLine("    <div class=\"delta-row\">")
        appendLine("      <span class=\"delta-label\">Δ</span>")
        appendLine("      <div class=\"plot\">")
        appendLine("        <div class=\"band\" style=\"left:${f(bandLo)}%;width:${f(bandHi - bandLo)}%\"></div>")
        appendLine("        <div class=\"zero\" style=\"left:${f(zero)}%\"></div>")
        appendLine("        <div class=\"ci $cls\" style=\"left:${f(minOf(ciLo, ciHi))}%;width:${f(abs(ciHi - ciLo))}%\"></div>")
        appendLine("        <div class=\"pt $cls\" style=\"left:${f(ptX)}%\"></div>")
        appendLine("      </div>")
        appendLine("    </div>")
        appendLine("    <div class=\"ticks\"><span>-${"%.3f".format(axisMax)}</span><span>0</span><span>+${"%.3f".format(axisMax)}</span></div>")
        appendLine("    <div class=\"delta-text\">" +
            "Δ ${"%+.3f".format(c.ci.point)} &nbsp; 95% CI [${"%+.3f".format(c.ci.lo)}, ${"%+.3f".format(c.ci.hi)}] &nbsp;·&nbsp; noise floor ±${"%.3f".format(c.noiseFloor)}" +
            "</div>")
        appendLine("  </div>")

        if (c.verdict == Verdict.REGRESSION && c.regressedCases.isNotEmpty()) {
            appendLine("  <div class=\"detail\">regressed cases: ${c.regressedCases.joinToString(", ")}</div>")
        }
        appendLine("</section>")
    }

    private fun StringBuilder.scoreBar(label: String, value: Double, cls: String) {
        appendLine("    <div class=\"score-row\">")
        appendLine("      <span class=\"score-label\">$label</span>")
        appendLine("      <div class=\"score-track\"><div class=\"score-fill $cls\" style=\"width:${f(value * 100)}%\"></div></div>")
        appendLine("      <span class=\"score-val\">${"%.3f".format(value)}</span>")
        appendLine("    </div>")
    }

    private fun f(v: Double) = "%.1f".format(v)

    private fun verdictClass(v: Verdict) = when (v) {
        Verdict.REGRESSION -> "critical"
        Verdict.IMPROVEMENT -> "good"
        Verdict.NO_CHANGE -> "muted"
    }

    private fun style() = """
        <style>
          :root{
            --surface:#fcfcfb; --plane:#f3f3f0; --ink:#0b0b0b; --ink2:#52514e; --muted:#898781;
            --grid:#e1e0d9; --axis:#9b9a93; --border:rgba(11,11,11,.12); --track:#eceae3;
            --good:#0ca30c; --critical:#d03b3b; --band:#e4e3dc;
          }
          @media (prefers-color-scheme: dark){
            :root{ --surface:#1a1a19; --plane:#0d0d0d; --ink:#fff; --ink2:#c3c2b7; --muted:#898781;
              --grid:#2c2c2a; --axis:#5a5955; --border:rgba(255,255,255,.14); --track:#2c2c2a;
              --good:#0ca30c; --critical:#d03b3b; --band:#2f2f2c; }
          }
          *{box-sizing:border-box}
          body{margin:0;background:var(--plane);color:var(--ink);
            font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;}
          .wrap{max-width:720px;margin:0 auto;padding:40px 24px 64px}
          header h1{font-size:22px;font-weight:650;margin:0}
          .sub{color:var(--muted);margin:4px 0 0;font-size:13px}
          .tiles{display:flex;flex-wrap:wrap;gap:10px;margin:24px 0}
          .tile{flex:1;min-width:104px;background:var(--surface);border:1px solid var(--border);
            border-radius:10px;padding:12px 14px}
          .tile-label{color:var(--muted);font-size:12px}
          .tile-value{font-size:19px;font-weight:650;margin-top:3px;font-variant-numeric:tabular-nums}
          .tile-value.mono{font-size:15px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
          .axis-legend{display:flex;gap:18px;color:var(--muted);font-size:12px;margin:4px 2px 10px;flex-wrap:wrap}
          .axis-legend .sw{display:inline-block;width:14px;height:10px;vertical-align:-1px;margin-right:5px;border-radius:2px}
          .axis-legend .sw.band{background:var(--band)}
          .axis-legend .sw.zero{width:2px;background:var(--axis);border-radius:0}
          .card{background:var(--surface);border:1px solid var(--border);border-radius:12px;
            padding:18px 20px;margin-bottom:14px}
          .card-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:14px}
          .metric{font-weight:650;font-size:15px}
          .n{color:var(--muted);font-size:13px;margin-left:6px}
          .badge{display:inline-flex;align-items:center;gap:7px;font-weight:650;font-size:13px}
          .ind{width:9px;height:9px;border-radius:50%;display:inline-block}
          .good{color:var(--good)} .critical{color:var(--critical)} .muted{color:var(--muted)}
          .ind.good{background:var(--good)} .ind.critical{background:var(--critical)} .ind.muted{background:var(--axis)}
          /* score bars */
          .scores{display:flex;flex-direction:column;gap:7px;margin-bottom:16px}
          .score-row{display:flex;align-items:center;gap:10px}
          .score-label{width:30px;color:var(--muted);font-size:12px}
          .score-track{flex:1;height:10px;background:var(--track);border-radius:5px;overflow:hidden}
          .score-fill{height:100%;border-radius:5px;background:currentColor}
          .score-fill.muted{background:var(--axis)}
          .score-val{width:48px;text-align:right;font-size:13px;font-variant-numeric:tabular-nums}
          /* delta plot */
          .delta-row{display:flex;align-items:center;gap:10px}
          .delta-label{width:30px;color:var(--muted);font-size:12px;font-style:italic}
          .plot{position:relative;flex:1;height:30px;background:var(--track);border-radius:6px;overflow:hidden}
          .band{position:absolute;top:0;bottom:0;background:var(--band)}
          .zero{position:absolute;top:0;bottom:0;width:2px;background:var(--axis);transform:translateX(-1px)}
          .ci{position:absolute;top:12px;height:6px;border-radius:3px;background:currentColor;opacity:.55}
          .pt{position:absolute;top:9px;width:12px;height:12px;border-radius:50%;background:currentColor;
            transform:translateX(-50%);box-shadow:0 0 0 2px var(--surface)}
          .ticks{display:flex;justify-content:space-between;color:var(--muted);font-size:11px;
            font-variant-numeric:tabular-nums;margin:4px 0 0 40px}
          .delta-text{color:var(--ink2);font-size:12.5px;font-variant-numeric:tabular-nums;margin:8px 0 0 40px}
          .detail{color:var(--muted);font-size:13px;margin-top:12px;padding-top:12px;border-top:1px solid var(--grid)}
          footer{color:var(--muted);font-size:12px;margin-top:20px;line-height:1.6}
          footer b{color:var(--ink2)}
        </style>
    """.trimIndent()
}
