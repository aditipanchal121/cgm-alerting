package com.aadiinfo.nightwatch.domain

import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.Thresholds
import com.aadiinfo.nightwatch.domain.model.TrendDirection
import com.aadiinfo.nightwatch.domain.model.TreatmentEvent
import kotlin.math.exp
import kotlin.math.pow

/** [projectedValue] is nullable so a predictor can decline to project at all
 * for the current conditions, rather than being forced to always produce
 * some number. [note] is shown in the UI alongside whatever this call does
 * produce - typically explaining why there's no projection this cycle. */
data class PredictionResult(val projectedValue: Double?, val minutesToThreshold: Double?, val note: String? = null)

/** CGMs don't report numeric values outside this range either - Dexcom
 * displays "LOW" below 40 and "HIGH" above 400 rather than a number - so a
 * projection is clamped to the same range a real reading could take. */
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

    /** Optional link to a fuller write-up of the underlying model, shown as
     * a tappable "Model details" link below [description] in the same
     * dialog. */
    val sourceUrl: String? get() = null

    fun predict(
        readings: List<GlucoseReading>,
        thresholds: Thresholds,
        horizonMinutes: Int = 30,
        treatments: List<TreatmentEvent> = emptyList()
    ): PredictionResult
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
        horizonMinutes: Int,
        treatments: List<TreatmentEvent>
    ): PredictionResult {
        val sorted = readings.sortedBy { it.dateMs }
        return linearProjection(sorted, thresholds.lowMgdl, horizonMinutes)
    }
}

/** Two-point linear extrapolation (oldest to newest of [sorted]) toward
 * [thresholdMgdl]. Shared by [LinearRegressionPredictor] (full window) and
 * [DirectionAwarePredictor] (an urgency-sized window). */
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
 * how much theoretical glucose-lowering effect the current IOB still has
 * relative to how much room there actually is left to fall - a
 * per-measurement ratio, not a fixed cutoff, so it means something different
 * for the same IOB value depending on the current reading.
 *
 * - Only downward trends are adjusted; a rising trend is left as plain
 *   linear extrapolation.
 * - `IOB x insulinSensitivityFactor` estimates the total remaining mg/dL of
 *   lowering effect still in the current IOB. Compared against how far the
 *   current reading sits above a physiological floor
 *   ([MIN_DISPLAYABLE_MGDL]), that gives a ratio: above 1 means there's
 *   plausibly enough insulin left to explain the fall continuing at least
 *   this fast, and can project a steeper fall than plain linear regression.
 *   Below 1, it tapers toward no further change.
 * - If the latest reading's IOB is flagged unreliable (see
 *   [GlucoseReading.iobUnreliable]), this falls back to the plain linear
 *   trend with no scaling at all.
 */
class IobAwarePredictor : GlucosePredictor {
    override val name = "IOB-aware trend"
    override val description =
        "Same straight-line trend as Linear regression, but scales a falling " +
            "projection by how much theoretical glucose-lowering effect " +
            "remains in the current IOB (IOB x insulin sensitivity factor) " +
            "relative to how far above a physiological floor the current " +
            "reading is - can project a steeper fall than plain linear when " +
            "that ratio is high, not just a gentler one. Falls back to the " +
            "plain trend if IOB is missing or flagged unreliable."

    override fun predict(
        readings: List<GlucoseReading>,
        thresholds: Thresholds,
        horizonMinutes: Int,
        treatments: List<TreatmentEvent>
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
        val canScale = observedRatePerMinute < 0 &&
            iob != null &&
            !last.iobUnreliable &&
            thresholds.insulinSensitivityFactor > 0.0
        val effectiveRatePerMinute = if (canScale) {
            // Coerced to at least 1 mg/dL to avoid dividing by zero or
            // flipping sign when the reading is already at/below the floor.
            val distanceAboveFloor = (last.sgv - MIN_DISPLAYABLE_MGDL).coerceAtLeast(1.0)
            val iobFactor = (iob!! * thresholds.insulinSensitivityFactor) / distanceAboveFloor
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
 * arrow is, then extrapolates that rate linearly ([linearProjection]):
 *
 * - Flat/diagonal (< 2 mg/dL/min) or unknown direction: the full fetched
 *   window.
 * - Single arrow (2-3 mg/dL/min): a ~20-minute window.
 * - Double arrow (>= 3 mg/dL/min): a 15-minute window, matching the Loop
 *   automated insulin delivery algorithm's own "glucose momentum"
 *   calculation (see loopkit.github.io/loopdocs/operation/algorithm/prediction).
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
        horizonMinutes: Int,
        treatments: List<TreatmentEvent>
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

/**
 * Constant-velocity Kalman filter over state [glucose, rate-of-change],
 * updated sequentially through the window's readings, then extrapolated
 * linearly from the final smoothed rate estimate. Each new reading is
 * weighted by the filter's own uncertainty (the Kalman gain), so a noisy
 * reading is partly discounted rather than taken at face value. Does not
 * estimate acceleration - the output is always a straight-line extrapolation
 * from the final smoothed rate.
 *
 * See:
 * - Welch G, Bishop G. "An Introduction to the Kalman Filter." UNC Chapel
 *   Hill, TR 95-041.
 * - Facchinetti A, Sparacino G, Cobelli C. "Real-Time Improvement of
 *   Continuous Glucose Monitoring Accuracy: The Smart Sensor Concept."
 *   Diabetes Care, 2013 (Kalman filtering applied specifically to CGM
 *   signal smoothing).
 */
class KalmanFilterPredictor : GlucosePredictor {
    override val name = "Kalman filter"
    override val description =
        "Recursively estimates a smoothed rate of change from the noisy CGM " +
            "readings, discounting ones its own uncertainty says are likely " +
            "just sensor noise, then extrapolates that single rate forward. " +
            "The standard technique for this exact problem - see Welch & " +
            "Bishop, \"An Introduction to the Kalman Filter\" (UNC Chapel " +
            "Hill TR 95-041), and Facchinetti et al. 2013, Diabetes Care, on " +
            "Kalman filtering for CGM signal smoothing specifically."

    override fun predict(
        readings: List<GlucoseReading>,
        thresholds: Thresholds,
        horizonMinutes: Int,
        treatments: List<TreatmentEvent>
    ): PredictionResult {
        val sorted = readings.sortedBy { it.dateMs }
        if (sorted.size < 2) {
            return PredictionResult(sorted.firstOrNull()?.sgv?.toDouble() ?: 0.0, null)
        }

        // State: [glucose (mg/dL), velocity (mg/dL per minute)]. Covariance
        // starts wide on velocity (no confidence yet in the initial guess of
        // 0) and narrows as readings arrive.
        var glucose = sorted.first().sgv.toDouble()
        var velocity = 0.0
        var pGG = MEASUREMENT_VARIANCE
        var pGV = 0.0
        var pVV = INITIAL_VELOCITY_VARIANCE

        var previousMs = sorted.first().dateMs
        for (i in 1 until sorted.size) {
            val reading = sorted[i]
            val dt = (reading.dateMs - previousMs) / 60_000.0
            previousMs = reading.dateMs
            if (dt <= 0) continue

            // Predict: propagate state and covariance forward by dt under a
            // constant-velocity model (P = F*P*F^T + Q, F = [[1,dt],[0,1]]),
            // adding process noise so velocity can still adapt to a genuine
            // trend change within the window instead of being smoothed away.
            glucose += velocity * dt
            val propagatedPGV = pGV + dt * pVV
            pGG += dt * (pGV + propagatedPGV) + PROCESS_VARIANCE_POSITION * dt
            pGV = propagatedPGV
            pVV += PROCESS_VARIANCE_VELOCITY * dt

            // Update: fold in this reading via the Kalman gain - a noisy
            // reading (large innovation relative to current uncertainty)
            // gets discounted rather than trusted at face value the way a
            // raw two-point or ordinary least-squares rate would be.
            val innovation = reading.sgv - glucose
            val innovationVariance = pGG + MEASUREMENT_VARIANCE
            val kalmanGainG = pGG / innovationVariance
            val kalmanGainV = pGV / innovationVariance

            glucose += kalmanGainG * innovation
            velocity += kalmanGainV * innovation

            val updatedPGG = (1 - kalmanGainG) * pGG
            val updatedPGV = (1 - kalmanGainG) * pGV
            pVV -= kalmanGainV * pGV
            pGG = updatedPGG
            pGV = updatedPGV
        }

        val projected = clampToDisplayable(glucose + velocity * horizonMinutes)
        if (velocity == 0.0) {
            return PredictionResult(projected, null)
        }

        val minutesToThreshold = (thresholds.lowMgdl - glucose) / velocity
        val crossesWithinHorizon = minutesToThreshold > 0 && minutesToThreshold <= horizonMinutes
        return PredictionResult(projected, if (crossesWithinHorizon) minutesToThreshold else null)
    }

    private companion object {
        // CGM sensor noise variance - a commonly cited standard deviation
        // for consumer CGMs is on the order of 10 mg/dL, squared here.
        const val MEASUREMENT_VARIANCE = 100.0 // (10 mg/dL)^2
        // Initial uncertainty on velocity - the filter starts knowing
        // nothing about the rate of change and resolves it over the first
        // several readings.
        const val INITIAL_VELOCITY_VARIANCE = 4.0
        // Process noise: how much the true glucose/velocity is expected to
        // wander per minute beyond what constant-velocity predicts, so the
        // filter can still track a genuine trend change within the window.
        const val PROCESS_VARIANCE_POSITION = 0.25
        const val PROCESS_VARIANCE_VELOCITY = 0.01
    }
}

/** Duration of insulin action and time-to-peak activity, in minutes, shared
 * across all boluses (a single insulin type is in use). */
private const val INSULIN_DURATION_MINUTES = 240.0
private const val INSULIN_PEAK_MINUTES = 75.0
private val INSULIN_DURATION_MS: Long = (INSULIN_DURATION_MINUTES * 60_000.0).toLong()

/** Fallback carb absorption duration, in minutes, used only when a
 * treatment has no `durationMinutes` of its own (Nightscout's per-entry
 * `absorptionTime`) - so a specific fast- or slow-absorbing entry, once
 * recorded, is used directly instead of assuming every carb behaves the
 * same. [CARB_PEAK_RATIO] reuses the same fraction-of-duration-to-peak
 * shape as the insulin curve, scaled to whatever duration applies. */
private const val CARB_DEFAULT_DURATION_MINUTES = 180.0
private const val CARB_PEAK_RATIO = INSULIN_PEAK_MINUTES / INSULIN_DURATION_MINUTES

/** Full derivation of [activityRemainingFraction]'s curve - see
 * [MultiBolusInsulinActivityPredictor.sourceUrl]. */
private const val INSULIN_MODEL_SOURCE_URL =
    "https://loopkit.github.io/loopdocs/operation/algorithm/insulin-modeling/"

/**
 * Fraction of a single dose (insulin or carbs) still active [minutesSinceDose]
 * after it was given, per the exponential action curve at
 * [INSULIN_MODEL_SOURCE_URL].
 *
 * Returns 1.0 (fully active) at t=0, decaying to 0.0 (fully used/absorbed) at
 * t=[durationMinutes], shaped so activity - the *rate* of insulin or carbs
 * being used, i.e. this function's negative slope - peaks at [peakMinutes]
 * rather than being front- or back-loaded.
 */
private fun activityRemainingFraction(
    minutesSinceDose: Double,
    durationMinutes: Double,
    peakMinutes: Double
): Double {
    if (minutesSinceDose <= 0.0) return 1.0
    if (minutesSinceDose >= durationMinutes) return 0.0

    val tau = peakMinutes * (1 - peakMinutes / durationMinutes) / (1 - 2 * peakMinutes / durationMinutes)
    val a = 2 * tau / durationMinutes
    val s = 1 / (1 - a + (1 + a) * exp(-durationMinutes / tau))
    val t = minutesSinceDose

    return 1 - s * (1 - a) * (
        (t.pow(2) / (tau * durationMinutes * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1
        )
}

private fun isActiveInsulin(treatment: TreatmentEvent, last: GlucoseReading): Boolean {
    val dose = treatment.insulin ?: return false
    val elapsedMs = last.dateMs - treatment.mills
    return dose > 0.0 && elapsedMs in 0 until INSULIN_DURATION_MS
}

private fun carbDurationMinutes(treatment: TreatmentEvent): Double =
    treatment.durationMinutes?.takeIf { it > 0.0 } ?: CARB_DEFAULT_DURATION_MINUTES

private fun isActiveCarb(treatment: TreatmentEvent, last: GlucoseReading): Boolean {
    val carbs = treatment.carbs ?: return false
    val elapsedMinutes = (last.dateMs - treatment.mills) / 60_000.0
    return carbs > 0.0 && elapsedMinutes in 0.0..carbDurationMinutes(treatment)
}

/**
 * Reads actual bolus and carb history (`treatments`, see backend/README.md)
 * and evaluates [activityRemainingFraction] at the real elapsed time since
 * each one. For every active bolus, the fraction of its insulin used between
 * now and the horizon - `IOB_frac(now) - IOB_frac(now + horizon)` -
 * converts to an expected glucose drop via the insulin sensitivity factor
 * (mg/dL per unit); each carb entry's absorbed fraction converts to an
 * expected glucose rise the same way, via a carb sensitivity factor derived
 * as `insulinSensitivityFactor / carbRatio` (there's no independently
 * measured carb sensitivity factor - see `Thresholds.carbRatio`). Every
 * treatment's contribution is computed independently and summed. See
 * android-app/README.md's "Where each value comes from" table for exactly
 * which field feeds which term.
 *
 * Does not drive real alerting (see alertEngine.ts server-side for that).
 */
class MultiBolusInsulinActivityPredictor : GlucosePredictor {
    override val name = "Multi-bolus insulin activity"
    override val description =
        "Reads actual bolus and carb history from Nightscout and evaluates a " +
            "standard exponential activity curve at the real elapsed time " +
            "since each one (see the model details link below), summing each " +
            "bolus's glucose-lowering effect and each carb entry's glucose-" +
            "raising effect over the next 30 minutes - overlapping treatments " +
            "are assumed additive. Carb effect is derived from your insulin " +
            "sensitivity factor and carb ratio, since there's no independently " +
            "measured carb sensitivity factor."
    override val sourceUrl = INSULIN_MODEL_SOURCE_URL

    override fun predict(
        readings: List<GlucoseReading>,
        thresholds: Thresholds,
        horizonMinutes: Int,
        treatments: List<TreatmentEvent>
    ): PredictionResult {
        val last = readings.maxByOrNull { it.dateMs }
            ?: return PredictionResult(null, null, "No readings yet.")
        if (thresholds.insulinSensitivityFactor <= 0.0) {
            return PredictionResult(last.sgv.toDouble(), null, "No insulin sensitivity factor set.")
        }

        val activeBoluses = treatments.filter { isActiveInsulin(it, last) }
        val activeCarbs = treatments.filter { isActiveCarb(it, last) }
        if (activeBoluses.isEmpty() && activeCarbs.isEmpty()) {
            return PredictionResult(last.sgv.toDouble(), null, "No active insulin or carbs.")
        }

        val totalInsulinDropMgdl = activeBoluses.sumOf { bolus ->
            val minutesSinceDose = (last.dateMs - bolus.mills) / 60_000.0
            val fractionUsedByHorizon =
                activityRemainingFraction(minutesSinceDose, INSULIN_DURATION_MINUTES, INSULIN_PEAK_MINUTES) -
                    activityRemainingFraction(
                        minutesSinceDose + horizonMinutes,
                        INSULIN_DURATION_MINUTES,
                        INSULIN_PEAK_MINUTES
                    )
            bolus.insulin!! * fractionUsedByHorizon * thresholds.insulinSensitivityFactor
        }

        val carbSensitivityFactor = if (thresholds.carbRatio > 0.0) {
            thresholds.insulinSensitivityFactor / thresholds.carbRatio
        } else {
            0.0
        }
        val totalCarbRiseMgdl = activeCarbs.sumOf { carb ->
            val minutesSinceDose = (last.dateMs - carb.mills) / 60_000.0
            val durationMinutes = carbDurationMinutes(carb)
            val peakMinutes = durationMinutes * CARB_PEAK_RATIO
            val fractionAbsorbedByHorizon =
                activityRemainingFraction(minutesSinceDose, durationMinutes, peakMinutes) -
                    activityRemainingFraction(minutesSinceDose + horizonMinutes, durationMinutes, peakMinutes)
            carb.carbs!! * fractionAbsorbedByHorizon * carbSensitivityFactor
        }

        val netDropMgdl = totalInsulinDropMgdl - totalCarbRiseMgdl
        val projected = clampToDisplayable(last.sgv - netDropMgdl)
        val effectiveRatePerMinute = -netDropMgdl / horizonMinutes
        if (effectiveRatePerMinute == 0.0) {
            return PredictionResult(projected, null)
        }

        val minutesToThreshold = (thresholds.lowMgdl - last.sgv) / effectiveRatePerMinute
        val crossesWithinHorizon = minutesToThreshold > 0 && minutesToThreshold <= horizonMinutes
        return PredictionResult(projected, if (crossesWithinHorizon) minutesToThreshold else null)
    }
}
