package com.aadiinfo.nightwatch.ui.predictions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.PredictionResult
import com.aadiinfo.nightwatch.domain.availablePredictors
import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.PatientPhysiology
import com.aadiinfo.nightwatch.domain.model.Thresholds
import com.aadiinfo.nightwatch.domain.model.TreatmentEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PredictorOutput(
    val predictorName: String,
    val description: String,
    val sourceUrl: String?,
    val result: PredictionResult
)

data class PredictionsUiState(
    val recentReadings: List<GlucoseReading> = emptyList(),
    val thresholds: Thresholds = Thresholds(),
    val outputs: List<PredictorOutput> = emptyList(),
    val loading: Boolean = true
)

/** Runs every registered [com.aadiinfo.nightwatch.domain.GlucosePredictor]
 * against the same recent window of readings so their outputs can be
 * compared side by side - purely for on-device experimentation, this does
 * not feed alerting (the backend's own predictor does that independently). */
class PredictionsViewModel(
    patientRepository: PatientRepository,
    patientId: String,
    uid: String
) : ViewModel() {

    val uiState: StateFlow<PredictionsUiState> = combine(
        patientRepository.observeRecentReadings(patientId, RECENT_READINGS_LIMIT),
        patientRepository.observeThresholds(patientId, uid),
        patientRepository.observeRecentTreatments(patientId, RECENT_TREATMENTS_LIMIT),
        patientRepository.observePatientPhysiology(patientId)
    ) { recent, thresholds, treatments, physiology ->
        // Trimmed here against current time on every emission, not via the
        // query's own bound - see observeRecentReadings's doc comment; a
        // fixed date cutoff computed once would drift since this listener
        // now lives for the whole app session (SharingStarted.Lazily).
        val cutoffMs = System.currentTimeMillis() - RECENT_WINDOW_MS
        val readings = recent.filter { it.dateMs >= cutoffMs }
        val outputs = availablePredictors.map { predictor ->
            PredictorOutput(
                predictor.name,
                predictor.description,
                predictor.sourceUrl,
                predictor.predict(readings, thresholds, treatments = treatments, physiology = physiology)
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

        // Covers the insulin duration-of-action window (240 min - see
        // GlucosePredictor.kt's INSULIN_DURATION_MINUTES) at typical
        // bolus/carb-correction frequency.
        const val RECENT_TREATMENTS_LIMIT = 20L
    }
}
