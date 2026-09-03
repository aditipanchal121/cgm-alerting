package com.aadiinfo.nightwatch.ui.connect

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.notifications.OmnipodIobListenerService
import kotlinx.coroutines.launch

/**
 * Single entry point for attaching this account/device to a family
 * member's existing patient record - replaces what used to be two
 * disconnected screens (JoinPatientScreen and IobSourceSetupScreen), each
 * with its own "Patient ID" text field. That split let someone enter the
 * *wrong* ID into one of them (e.g. their own patient instead of the
 * actual patient's) with no way to notice, since nothing tied the two
 * flows to the same value - see the postmortem in git history for
 * OmnipodIobListenerService.kt. Entering the ID once here and optionally
 * checking "this phone also reports IOB" guarantees both actions - if
 * both are wanted - always target the same patient.
 *
 * Always joins as a follower (a no-op if this account already owns or
 * follows that patient - see joinPatientAsFollower), so this screen is
 * also the correct one to reach for later if you only ever want to view
 * a family member's data. Reachable any time from MainScreen's toolbar,
 * not just before this account has its first patient - fixing a
 * misconfigured ID (like the one that motivated this rewrite) shouldn't
 * require reinstalling.
 */
@Composable
fun ConnectToPatientScreen(patientRepository: PatientRepository, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var patientId by remember { mutableStateOf(OmnipodIobListenerService.getPatientId(context) ?: "") }
    var displayName by remember { mutableStateOf("") }
    var alsoReportIob by remember { mutableStateOf(OmnipodIobListenerService.isEnabled(context)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Non-null once connecting succeeds - shown as a confirmation step rather
    // than navigating away immediately, so a wrong patient ID is visible
    // before it's too late to notice.
    var connectedName by remember { mutableStateOf<String?>(null) }

    var notificationAccessGranted by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccessGranted =
                    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val confirmedName = connectedName
    if (confirmedName != null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Connected to:", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                confirmedName.ifBlank { "(unnamed profile)" },
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Double check that's the right person before continuing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Continue")
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(0.dp, 0.dp, 0.dp, 8.dp)) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
        }
        Text("Connect to a family member", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "This is the ID for the profile of whoever's glucose is being " +
                "tracked (not your own) - ask them to open Settings and copy " +
                "the Profile ID, then enter it below.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = patientId,
            onValueChange = { patientId = it; error = null },
            label = { Text("Profile ID") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it; error = null },
            label = { Text("Your name") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("This phone also has the pump app", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Reports IOB directly from the pump app's own notification " +
                        "(currently supports Omnipod 5) for the same profile above - " +
                        "requires notification access, granted separately below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = alsoReportIob, onCheckedChange = { alsoReportIob = it })
        }

        if (alsoReportIob) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (notificationAccessGranted) "Notification access: granted" else "Notification access: not granted",
                style = MaterialTheme.typography.bodyMedium,
                color = if (notificationAccessGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open notification access settings")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Find Vigil in the list and turn it on - Android only allows " +
                    "granting this from system Settings, not from within the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                loading = true
                error = null
                scope.launch {
                    runCatching {
                        val name = patientRepository.joinPatientAsFollower(patientId, displayName)
                        if (alsoReportIob) {
                            patientRepository.claimIobSource(patientId)
                        }
                        // Configured for exactly the ID just confirmed above, whether
                        // enabling or leaving this device out of IOB reporting - never
                        // left pointed at a stale ID from a previous mistaken setup.
                        OmnipodIobListenerService.configure(context, patientId, alsoReportIob)
                        name
                    }.onSuccess { name -> connectedName = name }
                        .onFailure { error = it.message ?: "Could not connect - check the profile ID." }
                    loading = false
                }
            },
            enabled = !loading && patientId.isNotBlank() && displayName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.height(20.dp))
            else Text("Connect")
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
