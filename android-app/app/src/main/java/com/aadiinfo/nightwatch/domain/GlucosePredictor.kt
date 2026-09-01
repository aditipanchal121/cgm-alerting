package com.aadiinfo.nightwatch.domain

import com.aadiinfo.nightwatch.domain.model.GlucoseReading

data class PredictionResult(val projectedValue: Double, val minutesToThreshold: Double?)

/**
 * Extension point for glucose prediction. The authoritative predictor runs
 * server-side in the backend's `pollGlucose` function (see
 * backend/functions/src/predictor.ts, which this mirrors); this on-device
 * copy exists so new models can be previewed/tested against live data
 * before being ported server-side.
 */
interface GlucosePredictor {
    fun predict(readings: List<GlucoseReading>, thresholdMgdl: Int, horizonMinutes: Int = 30): PredictionResult
}

class LinearRegressionPredictor : GlucosePredictor {
    override fun predict(
        readings: List<GlucoseReading>,
        thresholdMgdl: Int,
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

        val minutesToThreshold = (thresholdMgdl - last.sgv) / ratePerMinute
        val crossesWithinHorizon = minutesToThreshold > 0 && minutesToThreshold <= horizonMinutes
        return PredictionResult(projected, if (crossesWithinHorizon) minutesToThreshold else null)
    }
}
