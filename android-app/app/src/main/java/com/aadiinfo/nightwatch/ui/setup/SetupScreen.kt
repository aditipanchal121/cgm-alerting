package com.aadiinfo.nightwatch.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
fun SetupScreen(
    patientRepository: PatientRepository,
    uid: String,
    existingPatientId: String? = null,
    onSetupComplete: (String) -> Unit
) {
    val viewModel: SetupViewModel = viewModel(
        factory = vmFactory { SetupViewModel(patientRepository, uid, existingPatientId) }
    )
    val state = viewModel.uiState

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        when (state.step) {
            SetupStep.CREATE_PATIENT -> {
                Text("Who are we monitoring?", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "This is the name family members will see in alerts.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = viewModel::onDisplayNameChange,
                    label = { Text("Display name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = viewModel::createPatient,
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.loading) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    else Text("Continue")
                }
            }

            SetupStep.ENTER_CREDENTIALS -> {
                Text("Connect Gluroo", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Enter the Nightscout-compatible URL and API secret from Gluroo " +
                        "Global Connect (Data settings in the Gluroo app).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = state.nightscoutUrl,
                    onValueChange = viewModel::onUrlChange,
                    label = { Text("Gluroo Global Connect URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.apiSecret,
                    onValueChange = viewModel::onSecretChange,
                    label = { Text("API secret") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.verifyAndSave { onSetupComplete(state.patientId!!) } },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.loading) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    else Text("Verify & save")
                }
            }
        }

        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        state.message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
    }
}
