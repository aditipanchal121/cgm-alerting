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

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.toThresholds(): Thresholds = Thresholds(
    units = this["units"] as? String ?: "mgdl",
    lowMgdl = (this["lowMgdl"] as? Number)?.toInt() ?: 80,
    urgentLowMgdl = (this["urgentLowMgdl"] as? Number)?.toInt() ?: 65,
    highMgdl = (this["highMgdl"] as? Number)?.toInt() ?: 200,
    urgentHighMgdl = (this["urgentHighMgdl"] as? Number)?.toInt() ?: 260,
    iobThreshold = (this["iobThreshold"] as? Number)?.toDouble() ?: 8.0,
    nightWindowStart = this["nightWindowStart"] as? String ?: "22:00",
    nightWindowEnd = this["nightWindowEnd"] as? String ?: "07:00",
    timezone = this["timezone"] as? String ?: TimeZone.getDefault().id,
    staleMinutes = (this["staleMinutes"] as? Number)?.toInt() ?: 20,
    enabledAlertTypes = (this["enabledAlertTypes"] as? List<*>)
        ?.mapNotNull { name -> runCatching { AlertType.valueOf(name.toString()) }.getOrNull() }
        ?.toSet()
        ?: DEFAULT_ENABLED_ALERT_TYPES
)

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
