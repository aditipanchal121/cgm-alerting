package com.aadiinfo.nightwatch.domain

import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.Thresholds

data class PredictionResult(val projectedValue: Double, val minutesToThreshold: Double?)

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

    fun predict(readings: List<GlucoseReading>, thresholds: Thresholds, horizonMinutes: Int = 30): PredictionResult
}

class LinearRegressionPredictor : GlucosePredictor {
    override val name = "Linear regression"

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

        val ratePerMinute = (last.sgv - first.sgv) / minutesElapsed
        val projected = last.sgv + ratePerMinute * horizonMinutes
        if (ratePerMinute == 0.0) {
            return PredictionResult(projected, null)
        }

        val minutesToThreshold = (thresholds.lowMgdl - last.sgv) / ratePerMinute
        val crossesWithinHorizon = minutesToThreshold > 0 && minutesToThreshold <= horizonMinutes
        return PredictionResult(projected, if (crossesWithinHorizon) minutesToThreshold else null)
    }
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

        val projected = last.sgv + effectiveRatePerMinute * horizonMinutes
        if (effectiveRatePerMinute == 0.0) {
            return PredictionResult(projected, null)
        }

        val minutesToThreshold = (thresholds.lowMgdl - last.sgv) / effectiveRatePerMinute
        val crossesWithinHorizon = minutesToThreshold > 0 && minutesToThreshold <= horizonMinutes
        return PredictionResult(projected, if (crossesWithinHorizon) minutesToThreshold else null)
    }
}
