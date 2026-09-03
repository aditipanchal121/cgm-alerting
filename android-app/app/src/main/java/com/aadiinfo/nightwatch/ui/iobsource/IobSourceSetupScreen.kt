package com.aadiinfo.nightwatch.ui.iobsource

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
 * Lets this device opt in to reporting IOB directly from a pump app's own
 * notification (e.g. a brother's phone that only has the pump app, or an
 * existing family member's phone that's also near the pump). Doesn't
 * require this device to be a family member of the patient at all -
 * deliberately self-service, entering the patient ID and flipping the
 * toggle is the whole flow, no separate owner-approval step, since the
 * patient ID is already this app's de facto shared-secret boundary (same
 * trust model as ESP32 device pairing). Every existing family member of
 * that patient sees the improved IOB automatically through the app's
 * normal Dashboard/notification/widget flow once this device starts
 * reporting - they don't need to do anything on their end.
 *
 * Reachable from VigilApp regardless of account state - a persistent
 * toolbar icon once a patient exists, or a link on the empty-state screen
 * before one does - rather than being nested inside just one of those
 * states, which previously hid it for any account that already had a
 * patient tied to it.
 */
@Composable
fun IobSourceSetupScreen(patientRepository: PatientRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var patientId by remember { mutableStateOf(OmnipodIobListenerService.getPatientId(context) ?: "") }
    var enabled by remember { mutableStateOf(OmnipodIobListenerService.isEnabled(context)) }
    var claiming by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // Notification access is granted externally in system Settings, not via
    // an in-app permission dialog - re-check it on resume so this reflects
    // reality after the user comes back from the Settings screen below.
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("IOB source setup", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Use this device to report IOB directly from a pump app's own " +
                "notification (currently supports Omnipod 5's \"Automated Mode\" / " +
                "\"Manual Mode\" notification) instead of relying on Gluroo's IOB " +
                "feed. Ask any family member for the patient ID below - everyone " +
                "already following that patient will automatically see the " +
                "improved IOB once this device starts reporting, with nothing to " +
                "set up on their end.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(24.dp))
        Text("Patient ID", style = MaterialTheme.typography.titleSmall)
        Text(
            "This identifies the record for whoever's glucose is being tracked " +
                "(not you personally) - copy it from that record's own Settings tab.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = patientId,
            onValueChange = { patientId = it; status = null },
            label = { Text("Patient ID") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Report IOB from this device", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Requires notification access, granted separately below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (claiming) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
            } else {
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            claiming = true
                            scope.launch {
                                runCatching { patientRepository.claimIobSource(patientId) }
                                    .onSuccess { patientDisplayName ->
                                        enabled = true
                                        OmnipodIobListenerService.configure(context, patientId, true)
                                        status = if (patientDisplayName.isNotBlank()) {
                                            "This device is now the IOB source for $patientDisplayName - " +
                                                "double check that's the right person."
                                        } else {
                                            "This device is now the IOB source for that patient."
                                        }
                                    }
                                    .onFailure {
                                        status = it.message ?: "Could not claim IOB source - check the patient ID."
                                    }
                                claiming = false
                            }
                        } else {
                            enabled = false
                            OmnipodIobListenerService.configure(context, patientId, false)
                            status = null
                        }
                    },
                    enabled = patientId.isNotBlank()
                )
            }
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))
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

        Spacer(Modifier.height(32.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}
