package com.aadiinfo.nightwatch.domain

import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.Thresholds
import com.aadiinfo.nightwatch.domain.model.TrendDirection
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** [projectedValue] is nullable so a predictor can decline to project at all
 * for the current conditions (see [QuadraticPredictor], which only fits a
 * curve for a steep trend) rather than being forced to always produce some
 * number. [note] is shown in the UI alongside whatever this call does
 * produce - typically explaining why there's no projection this cycle. */
data class PredictionResult(val projectedValue: Double?, val minutesToThreshold: Double?, val note: String? = null)

/** CGMs don't report numeric values outside this range either - Dexcom
 * displays "LOW" below 40 and "HIGH" above 400 rather than a number - so an
 * extrapolated projection has no business claiming more precision than the
 * sensor itself would. Without this, an aggressively-curving fit (the
 * quadratic model especially, extrapolating a handful of noisy points 30
 * minutes past the last one) can produce something like -1 mg/dL: correct
 * polynomial math, but physiologically meaningless and dangerous to show
 * next to a real reading. */
private const val MIN_DISPLAYABLE_MGDL = 40.0
private const val MAX_DISPLAYABLE_MGDL = 400.0

private fun clampToDisplayable(value: Double): Double = value.coerceIn(MIN_DISPLAYABLE_MGDL, MAX_DISPLAYABLE_MGDL)

/**
 * Extension point for glucose prediction. The authoritative predictor runs
 * server-side in the backend's `pollGlucose` function (see
 * backend/functions/src/predictor.ts, which this mirrors); this on-device
 * copy exists so new models can be previewed/tested against live data
 * before being ported server-side.
 */
interface GlucosePredictor {
    /** Shown on the Predictions tab to label this predictor's output. */
    val name: String

    /** Brief, plain-language explanation of this predictor's mechanism -
     * shown in a dialog when the user taps its info button on the
     * Predictions tab, so it's clear what makes each model different. */
    val description: String

    fun predict(readings: List<GlucoseReading>, thresholds: Thresholds, horizonMinutes: Int = 30): PredictionResult
}

class LinearRegressionPredictor : GlucosePredictor {
    override val name = "Linear regression"
    override val description =
        "Draws a straight line between your oldest and newest readings in the " +
            "window and extends it forward. Simple and predictable, but assumes " +
            "the current rate of change continues exactly as-is for the full " +
            "30 minutes."

    override fun predict(
        readings: List<GlucoseReading>,
        thresholds: Thresholds,
        horizonMinutes: Int
    ): PredictionResult {
        val sorted = readings.sortedBy { it.dateMs }
        return linearProjection(sorted, thresholds.lowMgdl, horizonMinutes)
    }
}

/** Two-point linear extrapolation (oldest to newest of [sorted]) toward
 * [thresholdMgdl]. Shared by [LinearRegressionPredictor] (full window),
 * [DirectionAwarePredictor] (an urgency-sized window), and as the fallback
 * path for [QuadraticPredictor] when a quadratic fit isn't well-determined. */
private fun linearProjection(
    sorted: List<GlucoseReading>,
    thresholdMgdl: Int,
    horizonMinutes: Int
): PredictionResult {
    if (sorted.size < 2) {
        return PredictionResult(sorted.firstOrNull()?.sgv?.toDouble() ?: 0.0, null)
    }

    val first = sorted.first()
    val last = sorted.last()
    val minutesElapsed = (last.dateMs - first.dateMs) / 60_000.0
    if (minutesElapsed <= 0) {
        return PredictionResult(last.sgv.toDouble(), null)
    }

    val ratePerMinute = (last.sgv - first.sgv) / minutesElapsed
    val projected = clampToDisplayable(last.sgv + ratePerMinute * horizonMinutes)
    if (ratePerMinute == 0.0) {
        return PredictionResult(projected, null)
    }

    val minutesToThreshold = (thresholdMgdl - last.sgv) / ratePerMinute
    val crossesWithinHorizon = minutesToThreshold > 0 && minutesToThreshold <= horizonMinutes
    return PredictionResult(projected, if (crossesWithinHorizon) minutesToThreshold else null)
}

/**
 * Adjusts the same observed linear trend as [LinearRegressionPredictor] by
 * how much active insulin (IOB) is still on board, on the theory that a
 * currently falling trend driven by insulin should taper off as that
 * insulin's effect runs out - not keep falling in a straight line for the
 * full horizon, which is exactly where plain linear extrapolation tends to
 * over-predict lows.
 *
 * This is deliberately NOT a real insulin activity/action curve model -
 * those need the time elapsed since each bolus, which isn't available here
 * (Gluroo's devicestatus only gives a live IOB snapshot, not a bolus
 * history). Instead it's a simpler, honestly-scoped heuristic:
 *
 * - Only downward trends are adjusted. A rising trend isn't something IOB
 *   explains, so it's left as plain linear extrapolation.
 * - The observed downward rate is trusted in full (unadjusted) when IOB is
 *   at or above the patient's IOB alert threshold - "there's plenty of
 *   active insulin, the fall is likely to continue."
 * - As IOB drops toward zero, the projected further fall is scaled down
 *   proportionally, tapering toward "no further net change" - "insulin is
 *   running out, the fall should flatten soon."
 * - If the latest reading's IOB is flagged unreliable (see
 *   [GlucoseReading.iobUnreliable] - Gluroo has been known to reset IOB to
 *   an implausible 0), that snapshot isn't trustworthy in either direction.
 *   Rather than guess, this falls back to the plain linear trend with no
 *   dampening at all - the same behavior as if IOB were unavailable.
 */
class IobAwarePredictor : GlucosePredictor {
    override val name = "IOB-aware trend"
    override val description =
        "Same straight-line trend as Linear regression, but tapers a falling " +
            "projection based on how much active insulin (IOB) remains - trusts " +
            "the fall fully when IOB is high, and scales it toward 'no further " +
            "change' as IOB runs low. Falls back to the plain trend if IOB is " +
            "missing or flagged unreliable."

    override fun predict(
        readings: List<GlucoseReading>,
        thresholds: Thresholds,
        horizonMinutes: Int
    ): PredictionResult {
        val sorted = readings.sortedBy { it.dateMs }
        if (sorted.size < 2) {
            return PredictionResult(sorted.firstOrNull()?.sgv?.toDouble() ?: 0.0, null)
        }

        val first = sorted.first()
        val last = sorted.last()
        val minutesElapsed = (last.dateMs - first.dateMs) / 60_000.0
        if (minutesElapsed <= 0) {
            return PredictionResult(last.sgv.toDouble(), null)
        }

        val observedRatePerMinute = (last.sgv - first.sgv) / minutesElapsed
        val iob = last.iob
        val canDampen = observedRatePerMinute < 0 &&
            iob != null &&
            !last.iobUnreliable &&
            thresholds.iobThreshold > 0.0
        val effectiveRatePerMinute = if (canDampen) {
            val iobFactor = (iob!! / thresholds.iobThreshold).coerceIn(0.0, 1.0)
            observedRatePerMinute * iobFactor
        } else {
            observedRatePerMinute
        }

        val projected = clampToDisplayable(last.sgv + effectiveRatePerMinute * horizonMinutes)
        if (effectiveRatePerMinute == 0.0) {
            return PredictionResult(projected, null)
        }

        val minutesToThreshold = (thresholds.lowMgdl - last.sgv) / effectiveRatePerMinute
        val crossesWithinHorizon = minutesToThreshold > 0 && minutesToThreshold <= horizonMinutes
        return PredictionResult(projected, if (crossesWithinHorizon) minutesToThreshold else null)
    }
}

private const val SINGLE_ARROW_WINDOW_MINUTES = 20
private const val DOUBLE_ARROW_WINDOW_MINUTES = 15

/**
 * Estimates the current rate of change from a window sized to how urgent the
 * arrow is, then extrapolates that rate linearly - the same model family
 * throughout ([linearProjection]); the arrow only changes how much history
 * gets averaged into the rate estimate:
 *
 * - Flat/diagonal (< 2 mg/dL/min) or unknown direction: the full fetched
 *   window. The trend is slow enough that more data safely smooths out
 *   noise without diluting anything meaningfully different.
 * - Single arrow (2-3 mg/dL/min): an intermediate ~20-minute window.
 * - Double arrow (>= 3 mg/dL/min): a 15-minute window, matching the Loop
 *   automated insulin delivery algorithm's own validated "glucose momentum"
 *   calculation (see loopkit.github.io/loopdocs/operation/algorithm/prediction) -
 *   the most urgent case gets the least-diluted, most current rate estimate,
 *   so a fast change that only just started isn't averaged down by calmer
 *   data from earlier in the window.
 *
 * This replaced an earlier design that used quadratic/exponential curve
 * fitting for steeper arrows. That didn't hold up under scrutiny: even
 * Loop - a widely used, real-world automated insulin delivery system - uses
 * plain linear regression for its own short-term rate estimate rather than
 * curve-fitting. It manages "how long can this rate be trusted" by fading
 * that rate's *influence* into a broader prediction that also models
 * insulin and carbs, not by assuming the rate itself decelerates. This
 * predictor has no such other components to hand off to, so extrapolating
 * the recent rate at full, undiminished strength for the whole horizon is
 * the safer choice than assuming - without evidence - that it levels off.
 * See [QuadraticPredictor] for the curve-fitting approach, kept as a
 * separate, clearly-experimental comparison point rather than deleted.
 *
 * The 15-minute double-arrow window is a direct match to Loop's own number;
 * the 20-minute single-arrow window is an engineering choice sitting
 * between that and the full window, not a literature-derived value.
 */
class DirectionAwarePredictor : GlucosePredictor {
    override val name = "Direction-aware (windowed)"
    override val description =
        "Estimates the current rate from a window sized to the arrow's " +
            "urgency, then extrapolates linearly - the full window for flat " +
            "or diagonal trends, about 20 minutes for a single arrow, and " +
            "15 minutes (matching the Loop automated insulin delivery " +
            "algorithm's own rate calculation) for a double arrow, so a " +
            "fast, recent change isn't averaged down by calmer data from " +
            "earlier in the window."

    override fun predict(
        readings: List<GlucoseReading>,
        thresholds: Thresholds,
        horizonMinutes: Int
    ): PredictionResult {
        val sorted = readings.sortedBy { it.dateMs }
        if (sorted.size < 2) {
            return PredictionResult(sorted.firstOrNull()?.sgv?.toDouble() ?: 0.0, null)
        }

        val direction = TrendDirection.fromNightscout(sorted.last().direction)
        val windowMinutes = when (direction) {
            TrendDirection.SINGLE_UP, TrendDirection.SINGLE_DOWN -> SINGLE_ARROW_WINDOW_MINUTES
            TrendDirection.DOUBLE_UP, TrendDirection.DOUBLE_DOWN -> DOUBLE_ARROW_WINDOW_MINUTES
            else -> null
        }

        return linearProjection(windowedReadings(sorted, windowMinutes), thresholds.lowMgdl, horizonMinutes)
    }
}

/** Restricts [sorted] to readings within [windowMinutes] of the newest one.
 * Returns [sorted] unchanged if [windowMinutes] is null (use the full
 * window) or if narrowing would leave fewer than 2 points to fit a line to. */
private fun windowedReadings(sorted: List<GlucoseReading>, windowMinutes: Int?): List<GlucoseReading> {
    if (windowMinutes == null) return sorted
    val cutoffMs = sorted.last().dateMs - windowMinutes * 60_000L
    val windowed = sorted.filter { it.dateMs >= cutoffMs }
    return if (windowed.size >= 2) windowed else sorted
}

/** Direction arrows steep enough for a curved fit to mean something -
 * single/double up or down. Flat, diagonal, and unknown/out-of-range
 * directions are excluded: with only a handful of noisy CGM points, fitting
 * a quadratic to a trend that isn't clearly moving is much more likely to
 * be reading curvature into noise than capturing real acceleration. */
private val QUADRATIC_ELIGIBLE_DIRECTIONS = setOf(
    TrendDirection.SINGLE_UP,
    TrendDirection.SINGLE_DOWN,
    TrendDirection.DOUBLE_UP,
    TrendDirection.DOUBLE_DOWN
)

private const val QUADRATIC_INELIGIBLE_NOTE =
    "No quadratic projection for this trend - this model only fits a curve " +
        "for a single or double up/down arrow, where there's a clear enough " +
        "move for curvature to mean something rather than just amplifying " +
        "noise on a flat or diagonal trend."

/**
 * Quadratic (2nd-order polynomial) least-squares fit, restricted to steep
 * trend arrows (see [QUADRATIC_ELIGIBLE_DIRECTIONS]) - kept as a separate,
 * clearly-experimental point of comparison after [DirectionAwarePredictor]
 * moved away from curve-fitting in favor of a windowed linear model (see
 * that class's doc comment for why). A quadratic can capture
 * acceleration/deceleration a straight line can't, but is more sensitive to
 * noise, especially with only 3-4 points - shown here specifically so that
 * sensitivity is visible on the comparison chart, not because it's assumed
 * to be more accurate than the other models. Restricting it to steep arrows
 * doesn't fix that sensitivity; it just avoids applying curve-fitting where
 * there isn't even a clear trend for a curve to plausibly describe.
 *
 * The fit is recency-weighted (see [recencyWeight]) rather than treating
 * every point in the window equally.
 */
class QuadraticPredictor : GlucosePredictor {
    override val name = "Quadratic (experimental)"
    override val description =
        "Fits a curved (quadratic) line instead of a straight one, weighting " +
            "your most recent readings more heavily than older ones. Only " +
            "runs for a single or double up/down arrow - kept as an " +
            "experimental comparison point rather than a recommended model, " +
            "since with only a handful of CGM readings it's prone to reading " +
            "curvature into what's really just noise."

    override fun predict(
        readings: List<GlucoseReading>,
        thresholds: Thresholds,
        horizonMinutes: Int
    ): PredictionResult {
        val sorted = readings.sortedBy { it.dateMs }
        val direction = TrendDirection.fromNightscout(sorted.last().direction)
        if (direction !in QUADRATIC_ELIGIBLE_DIRECTIONS) {
            return PredictionResult(projectedValue = null, minutesToThreshold = null, note = QUADRATIC_INELIGIBLE_NOTE)
        }
        if (sorted.size < 3) {
            return linearProjection(sorted, thresholds.lowMgdl, horizonMinutes)
        }
        return quadraticProjection(sorted, thresholds.lowMgdl, horizonMinutes)
    }
}

/** A point twice this many minutes older than the newest reading in the
 * window counts for a quarter as much in the fit, half again as many minutes
 * older counts for an eighth, and so on - see [recencyWeight]. Chosen so
 * that within a 30-minute window, the last ~10-15 minutes dominate the
 * curve while older points still contribute rather than being hard-cut. */
private const val QUADRATIC_RECENCY_HALF_LIFE_MINUTES = 10.0

/** Exponential recency decay weight for weighted least squares: a point
 * [ageMinutes] older than the newest reading gets `0.5^(ageMinutes /
 * halfLife)` of the weight a same-aged (age 0) point would get. This is the
 * same principle behind locally-weighted regression (LOESS) and
 * exponentially-weighted moving averages - recent points dominate the fit,
 * older points fade out smoothly rather than being weighted equally or
 * hard-excluded by a cutoff. */
private fun recencyWeight(ageMinutes: Double): Double =
    0.5.pow(ageMinutes / QUADRATIC_RECENCY_HALF_LIFE_MINUTES)

/** Weighted least-squares quadratic fit (y = a*x^2 + b*x + c, x in minutes
 * since the first reading) via Cramer's rule on the normal equations, with
 * each point weighted by [recencyWeight] so a drop that just started
 * accelerating isn't diluted by flatter data from earlier in the window -
 * in reality the most recent trend is the most informative about what
 * happens next. Falls back to [linearProjection] if the fit is degenerate
 * (e.g. near-duplicate timestamps making the quadratic term unidentifiable). */
private fun quadraticProjection(
    sorted: List<GlucoseReading>,
    thresholdMgdl: Int,
    horizonMinutes: Int
): PredictionResult {
    val t0 = sorted.first().dateMs
    val xs = sorted.map { (it.dateMs - t0) / 60_000.0 }
    val ys = sorted.map { it.sgv.toDouble() }
    val xLast = xs.last()

    var s0 = 0.0
    var s1 = 0.0
    var s2 = 0.0
    var s3 = 0.0
    var s4 = 0.0
    var sy = 0.0
    var sxy = 0.0
    var sx2y = 0.0
    for (i in xs.indices) {
        val x = xs[i]
        val y = ys[i]
        val w = recencyWeight(xLast - x)
        val x2 = x * x
        s0 += w
        s1 += w * x
        s2 += w * x2
        s3 += w * x2 * x
        s4 += w * x2 * x2
        sy += w * y
        sxy += w * x * y
        sx2y += w * x2 * y
    }

    // s0 (sum of weights) takes the role plain unweighted regression gives
    // to n (count of points) - unweighted least squares is just the special
    // case where every weight is 1, so s0 == n.
    val det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2)
    if (abs(det) < 1e-6) {
        return linearProjection(sorted, thresholdMgdl, horizonMinutes)
    }

    val detC = sy * (s2 * s4 - s3 * s3) - s1 * (sxy * s4 - s3 * sx2y) + s2 * (sxy * s3 - s2 * sx2y)
    val detB = s0 * (sxy * s4 - s3 * sx2y) - sy * (s1 * s4 - s3 * s2) + s2 * (s1 * sx2y - sxy * s2)
    val detA = s0 * (s2 * sx2y - sxy * s3) - s1 * (s1 * sx2y - sxy * s2) + sy * (s1 * s3 - s2 * s2)

    val c = detC / det
    val b = detB / det
    val a = detA / det

    val xHorizon = xLast + horizonMinutes
    val projected = clampToDisplayable(a * xHorizon * xHorizon + b * xHorizon + c)
    val minutesToThreshold = solveQuadraticCrossing(a, b, c, thresholdMgdl.toDouble(), xLast, horizonMinutes)
    return PredictionResult(projected, minutesToThreshold)
}

/** Solves a*x^2 + b*x + (c - threshold) = 0 for the soonest root after
 * [xLast] that falls within the horizon, returned as minutes from now. */
private fun solveQuadraticCrossing(
    a: Double,
    b: Double,
    c: Double,
    threshold: Double,
    xLast: Double,
    horizonMinutes: Int
): Double? {
    val constantTerm = c - threshold
    val roots = when {
        abs(a) < 1e-9 -> {
            if (abs(b) < 1e-9) return null
            listOf(-constantTerm / b)
        }
        else -> {
            val discriminant = b * b - 4 * a * constantTerm
            if (discriminant < 0) return null
            val sqrtDiscriminant = sqrt(discriminant)
            listOf((-b + sqrtDiscriminant) / (2 * a), (-b - sqrtDiscriminant) / (2 * a))
        }
    }

    return roots
        .map { it - xLast }
        .filter { it > 0 && it <= horizonMinutes }
        .minOrNull()
}
