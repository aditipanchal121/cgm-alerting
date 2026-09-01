package com.aadiinfo.nightwatch.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.Thresholds
import com.aadiinfo.nightwatch.domain.model.TrendDirection
import com.aadiinfo.nightwatch.ui.vmFactory
import java.util.concurrent.TimeUnit

@Composable
fun DashboardScreen(patientRepository: PatientRepository, patientId: String) {
    val viewModel: DashboardViewModel =
        viewModel(factory = vmFactory { DashboardViewModel(patientRepository, patientId) })
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            state.patient?.displayName ?: "NightWatch",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(24.dp))

        when {
            state.loading -> CircularProgressIndicator()
            state.reading == null -> Text(
                "No readings yet - the backend polls Gluroo every few minutes " +
                    "once credentials are saved.",
                style = MaterialTheme.typography.bodyMedium
            )
            else -> ReadingCard(state.reading!!, state.thresholds)
        }
    }
}

@Composable
private fun ReadingCard(reading: GlucoseReading, thresholds: Thresholds) {
    val color = glucoseColor(reading.sgv, thresholds)
    val trend = TrendDirection.fromNightscout(reading.direction)
    val ageMinutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - reading.dateMs)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${reading.sgv}",
            fontSize = 72.sp,
            color = color
        )
        Text(
            " ${trend.arrow}",
            fontSize = 40.sp,
            color = color
        )
    }
    Text("mg/dL", style = MaterialTheme.typography.bodyMedium)

    Spacer(Modifier.height(16.dp))
    Text(
        if (ageMinutes <= 1) "Updated just now" else "Updated $ageMinutes min ago",
        style = MaterialTheme.typography.bodyMedium,
        color = if (ageMinutes >= thresholds.staleMinutes) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    )

    reading.iob?.let { iob ->
        Spacer(Modifier.height(8.dp))
        Text(
            "IOB: ${"%.2f".format(iob)}u",
            style = MaterialTheme.typography.bodyMedium,
            color = if (iob >= thresholds.iobThreshold) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(24.dp))
    Text(
        "Range: ${thresholds.lowMgdl}-${thresholds.highMgdl} mg/dL " +
            "(urgent below ${thresholds.urgentLowMgdl}, above ${thresholds.urgentHighMgdl})",
        style = MaterialTheme.typography.bodySmall
    )
}

private fun glucoseColor(sgv: Int, thresholds: Thresholds): Color = when {
    sgv <= thresholds.urgentLowMgdl || sgv >= thresholds.urgentHighMgdl -> Color(0xFFD32F2F)
    sgv <= thresholds.lowMgdl || sgv >= thresholds.highMgdl -> Color(0xFFF9A825)
    else -> Color(0xFF2E7D32)
}
