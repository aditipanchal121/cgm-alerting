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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aadiinfo.nightwatch.notifications.OmnipodIobListenerService
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

/**
 * Lets a device that isn't a family member of any patient (e.g. a brother's
 * phone that only has the pump app, not a NightWatch account tied to this
 * patient) opt in to reporting IOB directly from that pump app's own
 * notification. Reachable from the empty-state chooser in NightWatchApp
 * when the signed-in account has no patients - deliberately doesn't require
 * joining as a family member, since being trusted to report IOB is a
 * narrower, orthogonal permission (see OmnipodIobListenerService and
 * backend/firestore.rules' externalIob rule).
 */
@Composable
fun IobSourceSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uid = Firebase.auth.currentUser?.uid ?: ""

    var patientId by remember { mutableStateOf(OmnipodIobListenerService.getPatientId(context) ?: "") }
    var enabled by remember { mutableStateOf(OmnipodIobListenerService.isEnabled(context)) }

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
                "notification (currently supports Omnipod 5's \"Automated Mode\" " +
                "notification) instead of relying on Gluroo's IOB feed. This " +
                "device doesn't need to be a family member of the patient - the " +
                "patient's owner just needs to authorize this account's ID below " +
                "as the trusted IOB source, from their own Settings tab.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(24.dp))
        Text("Your account ID", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Send this to the patient's owner so they can authorize this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                uid.ifBlank { "Sign in first" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { clipboardManager.setText(AnnotatedString(uid)) },
                enabled = uid.isNotBlank()
            ) {
                Text("Copy")
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Patient ID", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Ask the owner for this - it's shown in their app's Settings tab.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = patientId,
            onValueChange = { patientId = it },
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
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    OmnipodIobListenerService.configure(context, patientId.ifBlank { null }, checked)
                },
                enabled = patientId.isNotBlank()
            )
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
            "Find NightWatch in the list and turn it on - Android only allows " +
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
