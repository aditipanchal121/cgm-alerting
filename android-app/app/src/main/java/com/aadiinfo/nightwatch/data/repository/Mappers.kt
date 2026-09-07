package com.aadiinfo.nightwatch.data.repository

import com.aadiinfo.nightwatch.domain.model.AlertEvent
import com.aadiinfo.nightwatch.domain.model.AlertType
import com.aadiinfo.nightwatch.domain.model.DEFAULT_ENABLED_ALERT_TYPES
import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.LivePredictions
import com.aadiinfo.nightwatch.domain.model.LivePredictorOutput
import com.aadiinfo.nightwatch.domain.model.Patient
import com.aadiinfo.nightwatch.domain.model.PatientPhysiology
import com.aadiinfo.nightwatch.domain.model.Severity
import com.aadiinfo.nightwatch.domain.model.Thresholds
import com.aadiinfo.nightwatch.domain.model.TreatmentEvent
import com.google.firebase.firestore.DocumentSnapshot
import java.util.TimeZone

fun DocumentSnapshot.toPatient(): Patient? {
    if (!exists()) return null
    return Patient(
        id = id,
        displayName = getString("displayName") ?: "",
        ownerUid = getString("ownerUid") ?: "",
        nightscoutUrl = getString("nightscoutUrl") ?: ""
    )
}

fun DocumentSnapshot.toThresholds(): Thresholds? {
    if (!exists()) return null
    return Thresholds(
        units = getString("units") ?: "mgdl",
        lowMgdl = (getLong("lowMgdl") ?: 80L).toInt(),
        urgentLowMgdl = (getLong("urgentLowMgdl") ?: 65L).toInt(),
        highMgdl = (getLong("highMgdl") ?: 200L).toInt(),
        urgentHighMgdl = (getLong("urgentHighMgdl") ?: 260L).toInt(),
        iobThreshold = getDouble("iobThreshold") ?: 8.0,
        nightWindowStart = getString("nightWindowStart") ?: "22:00",
        nightWindowEnd = getString("nightWindowEnd") ?: "07:00",
        timezone = getString("timezone") ?: TimeZone.getDefault().id,
        staleMinutes = (getLong("staleMinutes") ?: 20L).toInt(),
        enabledAlertTypes = (get("enabledAlertTypes") as? List<*>)
            ?.mapNotNull { name -> runCatching { AlertType.valueOf(name.toString()) }.getOrNull() }
            ?.toSet()
            ?: DEFAULT_ENABLED_ALERT_TYPES
    )
}

fun Thresholds.toMap(): Map<String, Any?> = mapOf(
    "units" to units,
    "lowMgdl" to lowMgdl,
    "urgentLowMgdl" to urgentLowMgdl,
    "highMgdl" to highMgdl,
    "urgentHighMgdl" to urgentHighMgdl,
    "iobThreshold" to iobThreshold,
    "nightWindowStart" to nightWindowStart,
    "nightWindowEnd" to nightWindowEnd,
    "timezone" to timezone,
    "staleMinutes" to staleMinutes,
    "enabledAlertTypes" to enabledAlertTypes.map { it.name }
)

fun DocumentSnapshot.toPatientPhysiology(): PatientPhysiology? {
    if (!exists()) return null
    return PatientPhysiology(
        insulinSensitivityFactor = getDouble("insulinSensitivityFactor") ?: 40.0,
        carbRatio = getDouble("carbRatio") ?: 10.0
    )
}

fun PatientPhysiology.toMap(): Map<String, Any?> = mapOf(
    "insulinSensitivityFactor" to insulinSensitivityFactor,
    "carbRatio" to carbRatio
)

fun DocumentSnapshot.toGlucoseReading(): GlucoseReading? {
    if (!exists()) return null
    val sgv = getLong("sgv") ?: return null
    return GlucoseReading(
        sgv = sgv.toInt(),
        direction = getString("direction") ?: "NOT COMPUTABLE",
        dateMs = getLong("dateMs") ?: 0L,
        iob = getDouble("iob"),
        iobUnreliable = getBoolean("iobUnreliable") ?: false
    )
}

fun DocumentSnapshot.toTreatmentEvent(): TreatmentEvent? {
    if (!exists()) return null
    val mills = getLong("mills") ?: return null
    return TreatmentEvent(
        eventType = getString("eventType") ?: "Unknown",
        mills = mills,
        insulin = getDouble("insulin"),
        carbs = getDouble("carbs"),
        durationMinutes = getDouble("durationMinutes")
    )
}

fun DocumentSnapshot.toLivePredictions(): LivePredictions? {
    if (!exists()) return null
    val rawOutputs = get("outputs") as? List<*> ?: emptyList<Any?>()
    val outputs = rawOutputs.mapNotNull { raw ->
        val map = raw as? Map<*, *> ?: return@mapNotNull null
        LivePredictorOutput(
            key = map["key"] as? String ?: return@mapNotNull null,
            name = map["name"] as? String ?: "",
            description = map["description"] as? String ?: "",
            sourceUrl = map["sourceUrl"] as? String,
            projectedValue = (map["projectedValue"] as? Number)?.toDouble(),
            note = map["note"] as? String
        )
    }
    return LivePredictions(
        outputs = outputs,
        updatedAtMs = getLong("updatedAtMs") ?: 0L
    )
}

fun DocumentSnapshot.toAlertEvent(): AlertEvent = AlertEvent(
    id = id,
    type = runCatching { AlertType.valueOf(getString("type") ?: "") }.getOrDefault(AlertType.STALE_DATA),
    severity = runCatching { Severity.valueOf(getString("severity") ?: "") }.getOrDefault(Severity.INFO),
    value = getDouble("value"),
    message = getString("message") ?: "",
    timestamp = getLong("timestamp") ?: 0L
)
