package com.aadiinfo.nightwatch.ui.join

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import kotlinx.coroutines.launch

/**
 * Self-service equivalent of claimIobSource for ordinary "follower" access:
 * enter the patient ID a family member shares (see the copy button in
 * MainScreen's Settings tab) and this account becomes a read-only follower
 * on that patient immediately. No invite to send or accept - the app never
 * had a UI for that, and patientId already functions as this app's
 * shared-secret boundary elsewhere (IOB source claiming, ESP32 pairing).
 */
@Composable
fun JoinPatientScreen(patientRepository: PatientRepository, onJoined: () -> Unit) {
    val scope = rememberCoroutineScope()
    var patientId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Non-null once the join succeeds - shown as a confirmation step rather
    // than navigating away immediately, so a wrong patient ID (e.g. someone
    // else's record instead of the one intended) is visible before it's too
    // late to notice.
    var joinedPatientName by remember { mutableStateOf<String?>(null) }

    val confirmedName = joinedPatientName
    if (confirmedName != null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("You're now following:", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                confirmedName.ifBlank { "(unnamed patient)" },
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Double check that's the right person before continuing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onJoined, modifier = Modifier.fillMaxWidth()) {
                Text("Continue")
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Follow a family member", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "This is the ID for the record of whoever's glucose is being " +
                "tracked, not your own - ask them to open Settings and copy " +
                "the Patient ID, then enter it below.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = patientId,
            onValueChange = { patientId = it; error = null },
            label = { Text("Patient ID") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it; error = null },
            label = { Text("Your name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                loading = true
                error = null
                scope.launch {
                    runCatching { patientRepository.joinPatientAsFollower(patientId, displayName) }
                        .onSuccess { patientDisplayName -> joinedPatientName = patientDisplayName }
                        .onFailure { error = it.message ?: "Could not join - check the patient ID." }
                    loading = false
                }
            },
            enabled = !loading && patientId.isNotBlank() && displayName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.height(20.dp))
            else Text("Follow this patient")
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
