package com.aadiinfo.nightwatch.ui.mcupairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
fun McuPairingScreen(patientRepository: PatientRepository, patientId: String) {
    val viewModel: McuPairingViewModel =
        viewModel(factory = vmFactory { McuPairingViewModel(patientRepository, patientId) })
    val state = viewModel.uiState

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Pair a haptic alarm device (optional)", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Entirely optional - alerts already reach this phone (including the " +
                "full-screen critical alarm) with no device paired. This just adds " +
                "a second, stronger vibration source. If you don't have the ESP32 " +
                "hardware yet, there's nothing else to do here.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "On first boot the ESP32 opens a WiFi setup portal and shows a device " +
                "ID (also printed over serial). Connect it to your WiFi via that " +
                "portal, then enter its device ID here to link it to this patient.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.deviceId,
            onValueChange = viewModel::onDeviceIdChange,
            label = { Text("Device ID") },
            modifier = Modifier.fillMaxWidth()
        )

        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (state.paired) {
            Spacer(Modifier.height(12.dp))
            Text("Paired! It should start receiving alerts within a few minutes.", color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = viewModel::pair, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.loading) "Pairing..." else "Pair device")
        }
    }
}
