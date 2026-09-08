package com.aadiinfo.nightwatch.domain.model

import java.util.TimeZone

enum class Severity { INFO, WARNING, CRITICAL }

enum class AlertType {
    LOW, URGENT_LOW, HIGH, URGENT_HIGH, IOB_HIGH, IOB_UNRELIABLE, STALE_DATA, PREDICTED_LOW, COMPRESSION_LOW
}

/** Mirrors Nightscout's `direction` values so the dashboard can show a trend arrow. */
enum class TrendDirection(val nightscoutValue: String, val arrow: String) {
    FLAT("Flat", "→"),
    FORTY_FIVE_UP("FortyFiveUp", "↗"),
    SINGLE_UP("SingleUp", "↑"),
    DOUBLE_UP("DoubleUp", "⇈"),
    FORTY_FIVE_DOWN("FortyFiveDown", "↘"),
    SINGLE_DOWN("SingleDown", "↓"),
    DOUBLE_DOWN("DoubleDown", "⇊"),
    NOT_COMPUTABLE("NOT COMPUTABLE", "?"),
    RATE_OUT_OF_RANGE("RATE OUT OF RANGE", "⇕");

    companion object {
        fun fromNightscout(value: String?): TrendDirection =
            entries.firstOrNull { it.nightscoutValue == value } ?: NOT_COMPUTABLE
    }
}

data class GlucoseReading(
    val sgv: Int,
    val direction: String,
    val dateMs: Long,
    val iob: Double?,
    val iobUnreliable: Boolean = false
)

data class AlertEvent(
    val id: String = "",
    val type: AlertType,
    val severity: Severity,
    val value: Double?,
    val message: String,
    val timestamp: Long
)

data class Thresholds(
    val units: String = "mgdl",
    val lowMgdl: Int = 80,
    val urgentLowMgdl: Int = 65,
    val highMgdl: Int = 200,
    val urgentHighMgdl: Int = 260,
    val iobThreshold: Double = 8.0,
    val nightWindowStart: String = "22:00",
    val nightWindowEnd: String = "07:00",
    val timezone: String = TimeZone.getDefault().id,
    val staleMinutes: Int = 20,
    // Which alert types this member wants to be notified about at all - an
    // event of a type not in this set is never generated for them.
    val enabledAlertTypes: Set<AlertType> = DEFAULT_ENABLED_ALERT_TYPES
)

// PREDICTED_LOW excluded by default - it's a linear-extrapolation guess (see
// backend/functions/src/predictor.ts), off until a member explicitly turns
// it on knowing that. Every other type defaults on.
val DEFAULT_ENABLED_ALERT_TYPES: Set<AlertType> = AlertType.entries.toSet() - AlertType.PREDICTED_LOW

/** Physiological facts about the patient, not personal alerting preferences -
 * shared across the whole family (patients/{patientId}/thresholds/current)
 * rather than per-member like Thresholds above, since there's only one real
 * insulin sensitivity/carb ratio regardless of who's viewing the app. */
data class PatientPhysiology(
    // mg/dL that 1 unit of insulin is expected to lower glucose by.
    val insulinSensitivityFactor: Double = 40.0,
    // Grams of carbs covered by 1 unit of insulin (insulin-to-carb ratio).
    val carbRatio: Double = 10.0
)

/** One experimental predictor's latest output - see
 * backend/functions-predict/predictors.py, the single source of truth for
 * this math (no on-device copy exists). [projectedValue] is null when the
 * predictor declined to project anything this cycle; [note] explains why,
 * shown alongside whatever this call does produce. */
data class LivePredictorOutput(
    val key: String = "",
    val name: String = "",
    val description: String = "",
    val sourceUrl: String? = null,
    val projectedValue: Double? = null,
    val note: String? = null
)

/** patients/{patientId}/livePredictions/current - computed once per patient
 * per poll cycle server-side, overwritten each time (same pattern as
 * externalIob/current). */
data class LivePredictions(
    val outputs: List<LivePredictorOutput> = emptyList(),
    val updatedAtMs: Long = 0L
)

data class Patient(
    val id: String = "",
    val displayName: String = "",
    val ownerUid: String = "",
    val nightscoutUrl: String = ""
)

data class Member(
    val uid: String = "",
    val role: String = "follower",
    val displayName: String = ""
)
