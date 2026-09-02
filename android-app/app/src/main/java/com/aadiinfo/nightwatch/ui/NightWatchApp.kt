package com.aadiinfo.nightwatch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aadiinfo.nightwatch.data.repository.AuthRepository
import com.aadiinfo.nightwatch.data.repository.FcmTokenRepository
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.Patient
import com.aadiinfo.nightwatch.ui.alertsettings.AlertSettingsScreen
import com.aadiinfo.nightwatch.ui.auth.LoginScreen
import com.aadiinfo.nightwatch.ui.dashboard.DashboardScreen
import com.aadiinfo.nightwatch.ui.history.HistoryScreen
import com.aadiinfo.nightwatch.ui.iobsource.IobSourceSetupScreen
import com.aadiinfo.nightwatch.ui.mcupairing.McuPairingScreen
import com.aadiinfo.nightwatch.ui.predictions.PredictionsScreen
import com.aadiinfo.nightwatch.ui.setup.SetupScreen
import com.aadiinfo.nightwatch.ui.theme.NightWatchTheme
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun NightWatchApp(
    authRepository: AuthRepository,
    patientRepository: PatientRepository,
    fcmTokenRepository: FcmTokenRepository
) {
    NightWatchTheme {
        val user by authRepository.authState.collectAsState(initial = authRepository.currentUser)
        val currentUser = user

        if (currentUser == null) {
            LoginScreen(authRepository) { /* authState flips automatically on success */ }
            return@NightWatchTheme
        }

        val uid = currentUser.uid

        // onNewToken (NightWatchFcmService) only fires when FCM mints or
        // rotates a token, which typically already happened at first app
        // launch - before sign-in, when there's no uid to register it under.
        // That token then sits unused for months, so nothing ever gets
        // written to users/{uid}/fcmTokens and every push silently has zero
        // recipients. Explicitly fetching+registering the current token here
        // once a user is signed in covers that gap.
        LaunchedEffect(uid) {
            runCatching { Firebase.messaging.token.await() }
                .onSuccess { token -> runCatching { fcmTokenRepository.registerToken(uid, token) } }
        }
        val patients by patientRepository.patientsForUser(uid).collectAsState(initial = null)

        when (val list = patients) {
            null -> LoadingScreen()
            else -> if (list.isEmpty()) {
                // A device with no patients isn't necessarily starting a new
                // one - it might be a family member's phone (e.g. one that
                // only has a pump app installed) meant purely to report IOB,
                // which deliberately doesn't require joining as a family
                // member (see IobSourceSetupScreen).
                var showIobSourceSetup by remember { mutableStateOf(false) }
                if (showIobSourceSetup) {
                    IobSourceSetupScreen(onBack = { showIobSourceSetup = false })
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        SetupScreen(patientRepository, uid) { /* patient list flow refreshes on write */ }
                        TextButton(
                            onClick = { showIobSourceSetup = true },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
                        ) {
                            Text("This device belongs to a family member's pump instead?")
                        }
                    }
                }
            } else {
                val patient = list.first()
                if (patient.nightscoutUrl.isBlank() && patient.ownerUid == uid) {
                    SetupScreen(patientRepository, uid, existingPatientId = patient.id) { }
                } else {
                    MainScreen(
                        patientRepository = patientRepository,
                        patient = patient,
                        isOwner = patient.ownerUid == uid,
                        onSignOut = authRepository::signOut
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

private enum class Tab(val label: String) {
    DASHBOARD("Dashboard"),
    HISTORY("History"),
    PREDICTIONS("Predictions"),
    SETTINGS("Settings"),
    PAIR_DEVICE("Alarm")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    patientRepository: PatientRepository,
    patient: Patient,
    isOwner: Boolean,
    onSignOut: () -> Unit
) {
    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
    val tabs = if (isOwner) Tab.entries else listOf(Tab.DASHBOARD, Tab.HISTORY, Tab.PREDICTIONS)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(patient.displayName) },
                actions = {
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.Filled.ExitToApp, contentDescription = "Sign out")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(tabIcon(t), contentDescription = t.label) },
                        // A wrapped two-line label makes that item's icon sit
                        // out of alignment with the single-line ones next to
                        // it - force single-line so a longer label (or a
                        // larger system font size) degrades to an ellipsis
                        // instead of breaking layout.
                        label = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.DASHBOARD -> DashboardScreen(patientRepository, patient.id)
                Tab.HISTORY -> HistoryScreen(patientRepository, patient.id)
                Tab.PREDICTIONS -> PredictionsScreen(patientRepository, patient.id)
                Tab.SETTINGS -> if (isOwner) {
                    var editingConnection by remember { mutableStateOf(false) }
                    if (editingConnection) {
                        SetupScreen(
                            patientRepository = patientRepository,
                            uid = patient.ownerUid,
                            existingPatientId = patient.id,
                            initialNightscoutUrl = patient.nightscoutUrl
                        ) { editingConnection = false }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            OutlinedButton(
                                onClick = { editingConnection = true },
                                modifier = Modifier.fillMaxWidth().padding(24.dp, 24.dp, 24.dp, 0.dp)
                            ) {
                                Text("Edit Gluroo connection")
                            }
                            Spacer(Modifier.height(16.dp))
                            IobSourceOwnerSection(patientRepository, patient.id)
                            Spacer(Modifier.height(16.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                AlertSettingsScreen(patientRepository, patient.id)
                            }
                        }
                    }
                } else {
                    Text("Only the owner can edit thresholds", modifier = Modifier.padding(24.dp))
                }
                Tab.PAIR_DEVICE -> if (isOwner) {
                    McuPairingScreen(patientRepository, patient.id)
                } else {
                    Text("Only the owner can pair an alarm device", modifier = Modifier.padding(24.dp))
                }
            }
        }
    }
}

/** Lets the owner authorize a family member's device to report IOB directly
 * (see IobSourceSetupScreen and backend/firestore.rules' externalIob rule) -
 * that device doesn't need to be a family member itself, just this UID. */
@Composable
private fun IobSourceOwnerSection(patientRepository: PatientRepository, patientId: String) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var sourceUid by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text("IOB source", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Let a family member's phone report IOB directly from their pump " +
                "app's own notification, instead of relying on Gluroo's IOB feed. " +
                "Share this patient ID with them, then paste their account ID below " +
                "once they've set up their device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Patient ID: $patientId",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { clipboardManager.setText(AnnotatedString(patientId)) }) {
                Text("Copy")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = sourceUid,
            onValueChange = { sourceUid = it; status = null },
            label = { Text("Family member's account ID") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                scope.launch {
                    runCatching { patientRepository.setIobSource(patientId, sourceUid) }
                        .onSuccess { status = "Saved." }
                        .onFailure { status = it.message ?: "Failed to save." }
                }
            },
            enabled = sourceUid.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Set as IOB source")
        }
        status?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun tabIcon(tab: Tab) = when (tab) {
    Tab.DASHBOARD -> Icons.Filled.Favorite
    Tab.HISTORY -> Icons.Filled.History
    Tab.PREDICTIONS -> Icons.Filled.Insights
    Tab.SETTINGS -> Icons.Filled.Settings
    Tab.PAIR_DEVICE -> Icons.Filled.Bluetooth
}
