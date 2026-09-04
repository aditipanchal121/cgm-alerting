package com.aadiinfo.nightwatch.ui.alertsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.ui.vmFactory

@Composable
fun AlertSettingsScreen(patientRepository: PatientRepository, patientId: String, uid: String) {
    val viewModel: AlertSettingsViewModel =
        viewModel(factory = vmFactory { AlertSettingsViewModel(patientRepository, patientId, uid) })
    val state = viewModel.uiState

    if (state.loading) {
        CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Your alert thresholds", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Personal to this account - each family member sets their own, " +
                "so your alerts may differ from what others see.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        Row {
            OutlinedTextField(
                value = state.urgentLowMgdl,
                onValueChange = { v -> viewModel.update { it.copy(urgentLowMgdl = v) } },
                label = { Text("Urgent low") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = state.lowMgdl,
                onValueChange = { v -> viewModel.update { it.copy(lowMgdl = v) } },
                label = { Text("Low") },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(
                value = state.highMgdl,
                onValueChange = { v -> viewModel.update { it.copy(highMgdl = v) } },
                label = { Text("High") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = state.urgentHighMgdl,
                onValueChange = { v -> viewModel.update { it.copy(urgentHighMgdl = v) } },
                label = { Text("Urgent high") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.iobThreshold,
            onValueChange = { v -> viewModel.update { it.copy(iobThreshold = v) } },
            label = { Text("IOB threshold (units)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))
        Text("Night window (used to escalate low alerts to critical)", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(
                value = state.nightWindowStart,
                onValueChange = { v -> viewModel.update { it.copy(nightWindowStart = v) } },
                label = { Text("Start (HH:MM)") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = state.nightWindowEnd,
                onValueChange = { v -> viewModel.update { it.copy(nightWindowEnd = v) } },
                label = { Text("End (HH:MM)") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.staleMinutes,
            onValueChange = { v -> viewModel.update { it.copy(staleMinutes = v) } },
            label = { Text("Signal-loss alert after (minutes)") },
            modifier = Modifier.fillMaxWidth()
        )

        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (state.saved) {
            Spacer(Modifier.height(12.dp))
            Text("Saved", color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = viewModel::save,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.saving) "Saving..." else "Save")
        }
    }
}
