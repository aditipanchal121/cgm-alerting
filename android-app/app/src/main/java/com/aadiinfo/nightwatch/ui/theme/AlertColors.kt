package com.aadiinfo.nightwatch.ui.theme

import androidx.compose.ui.graphics.Color
import com.aadiinfo.nightwatch.domain.model.AlertType
import com.aadiinfo.nightwatch.domain.model.Severity
import com.aadiinfo.nightwatch.domain.model.Thresholds

/** Shared alert-type palette - used by the History log, the Dashboard's
 * reading number + trend graph, and the Predictions graph, so the same
 * condition always reads as the same color everywhere it shows up. */
object AlertColors {
    val UrgentLow = Color(0xFFD32F2F) // red
    val Low = Color(0xFFF57C00) // orange
    val Normal = Color(0xFF2E7D32) // green
    val High = Color(0xFF3F51B5) // indigo
    val UrgentHigh = Color(0xFF9C27B0) // violet
    val PredictedLow = Color(0xFFF4C2C2) // baby pink

    /** Colors an alert by its specific type where one's been called out
     * (see the conversation this palette was defined in); types without a
     * specific color fall back to a plain severity read since they're not
     * about a glucose zone (e.g. IOB_HIGH, STALE_DATA). */
    fun forAlertType(type: AlertType, severity: Severity): Color = when (type) {
        AlertType.URGENT_LOW -> UrgentLow
        AlertType.LOW -> Low
        AlertType.HIGH -> High
        AlertType.URGENT_HIGH -> UrgentHigh
        AlertType.PREDICTED_LOW -> PredictedLow
        else -> when (severity) {
            Severity.CRITICAL -> UrgentLow
            Severity.WARNING -> Low
            Severity.INFO -> Color(0xFF616161)
        }
    }

    /** Colors a glucose value by which zone it currently falls in - the same
     * red/orange/green/indigo/violet used for the equivalent alert types
     * above, so a reading that's currently LOW is the same orange as a
     * logged LOW alert. */
    fun forGlucoseZone(sgv: Int, thresholds: Thresholds): Color = when {
        sgv <= thresholds.urgentLowMgdl -> UrgentLow
        sgv <= thresholds.lowMgdl -> Low
        sgv >= thresholds.urgentHighMgdl -> UrgentHigh
        sgv >= thresholds.highMgdl -> High
        else -> Normal
    }
}
