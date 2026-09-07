package com.aadiinfo.nightwatch.ui.predictions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.Thresholds
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PredictorOutput(
    val key: String,
    val predictorName: String,
    val description: String,
    val sourceUrl: String?,
    val projectedValue: Double?,
    val minutesToThreshold: Double?,
    val note: String?
)

data class PredictionsUiState(
    val recentReadings: List<GlucoseReading> = emptyList(),
    val thresholds: Thresholds = Thresholds(),
    val outputs: List<PredictorOutput> = emptyList(),
    val loading: Boolean = true
)

private const val PREDICTION_HORIZON_MINUTES = 30.0

/** Same linear-interpolation-from-current-to-projected estimate every
 * predictor already computes for itself server-side (see predictors.py) -
 * this is the one genuinely per-viewer piece (each member has their own
 * lowMgdl), so it's derived here from the shared projected value rather
 * than needing its own model implementation. */
private fun deriveMinutesToThreshold(currentSgv: Int, projectedValue: Double?, lowMgdl: Int): Double? {
    if (projectedValue == null) return null
    val effectiveRatePerMinute = (projectedValue - currentSgv) / PREDICTION_HORIZON_MINUTES
    if (effectiveRatePerMinute == 0.0) return null
    val minutesToThreshold = (lowMgdl - currentSgv) / effectiveRatePerMinute
    val crossesWithinHorizon = minutesToThreshold > 0 && minutesToThreshold <= PREDICTION_HORIZON_MINUTES
    return if (crossesWithinHorizon) minutesToThreshold else null
}

/** The actual model computation lives entirely server-side now (see
 * backend/functions-predict/predictors.py, the single source of truth) -
 * this ViewModel only reads patients/{id}/livePredictions/current (written
 * once per patient per poll cycle) and derives each viewer's own
 * threshold-crossing estimate locally. Purely for on-device comparison;
 * doesn't feed alerting (the backend's own predictor does that
 * independently). */
class PredictionsViewModel(
    patientRepository: PatientRepository,
    patientId: String,
    uid: String
) : ViewModel() {

    val uiState: StateFlow<PredictionsUiState> = combine(
        patientRepository.observeRecentReadings(patientId, RECENT_READINGS_LIMIT),
        patientRepository.observeThresholds(patientId, uid),
        patientRepository.observeLivePredictions(patientId)
    ) { recent, thresholds, livePredictions ->
        // Trimmed here against current time on every emission, not via the
        // query's own bound - see observeRecentReadings's doc comment; a
        // fixed date cutoff computed once would drift since this listener
        // now lives for the whole app session (SharingStarted.Lazily).
        val cutoffMs = System.currentTimeMillis() - RECENT_WINDOW_MS
        val readings = recent.filter { it.dateMs >= cutoffMs }
        val currentSgv = readings.lastOrNull()?.sgv
        val outputs = livePredictions.outputs.map { output ->
            PredictorOutput(
                key = output.key,
                predictorName = output.name,
                description = output.description,
                sourceUrl = output.sourceUrl,
                projectedValue = output.projectedValue,
                minutesToThreshold = currentSgv?.let {
                    deriveMinutesToThreshold(it, output.projectedValue, thresholds.lowMgdl)
                },
                note = output.note
            )
        }
        PredictionsUiState(
            recentReadings = readings,
            thresholds = thresholds,
            outputs = outputs,
            loading = false
        )
    // Lazily, not WhileSubscribed - see DashboardViewModel's comment.
    }.stateIn(viewModelScope, SharingStarted.Lazily, PredictionsUiState())

    private companion object {
        // Matches the backend's own PREDICTED_LOW window (alertEngine.ts calls
        // predictMinutesToThreshold with the same ~6 recent Gluroo entries
        // pollGlucose just fetched, i.e. ~30 min at Gluroo's ~5-min cadence).
        const val RECENT_WINDOW_MS = 30 * 60 * 1000L

        // ~6 readings in 30 min at the usual 5-minute cadence.
        const val RECENT_READINGS_LIMIT = 12L
    }
}
