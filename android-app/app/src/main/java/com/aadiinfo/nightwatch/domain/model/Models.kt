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
    val timestamp: Long,
    val acknowledged: Boolean = false
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
    val staleMinutes: Int = 20
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
