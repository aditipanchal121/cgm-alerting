package com.aadiinfo.nightwatch.ui

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aadiinfo.nightwatch.R
import com.aadiinfo.nightwatch.data.repository.AuthRepository
import com.aadiinfo.nightwatch.data.repository.FcmTokenRepository
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.Patient
import com.aadiinfo.nightwatch.ui.alertsettings.AlertSettingsScreen
import com.aadiinfo.nightwatch.ui.auth.LoginScreen
import com.aadiinfo.nightwatch.ui.dashboard.DashboardScreen
import com.aadiinfo.nightwatch.ui.history.HistoryScreen
import com.aadiinfo.nightwatch.ui.iobsource.IobSourceSetupScreen
import com.aadiinfo.nightwatch.ui.join.JoinPatientScreen
import com.aadiinfo.nightwatch.ui.mcupairing.McuPairingScreen
import com.aadiinfo.nightwatch.ui.predictions.PredictionsScreen
import com.aadiinfo.nightwatch.ui.setup.SetupScreen
import com.aadiinfo.nightwatch.ui.theme.VigilTheme
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging
import kotlinx.coroutines.tasks.await

@Composable
fun VigilApp(
    authRepository: AuthRepository,
    patientRepository: PatientRepository,
    fcmTokenRepository: FcmTokenRepository
) {
    VigilTheme {
        val user by authRepository.authState.collectAsState(initial = authRepository.currentUser)
        val currentUser = user

        if (currentUser == null) {
            LoginScreen(authRepository) { /* authState flips automatically on success */ }
            return@VigilTheme
        }

        val uid = currentUser.uid

        // onNewToken (VigilFcmService) only fires when FCM mints or
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

        // Reachable from every post-login state (not nested inside the
        // "zero patients" branch, which is where this used to live - that
        // silently hid it for any account that already had a patient tied
        // to it, e.g. a leftover test one). See MainScreen's toolbar icon
        // for the entry point once a patient exists, and the empty-state
        // link below for before one does.
        var showIobSourceSetup by remember { mutableStateOf(false) }
        if (showIobSourceSetup) {
            IobSourceSetupScreen(patientRepository, onBack = { showIobSourceSetup = false })
            return@VigilTheme
        }

        // Same reasoning as showIobSourceSetup above: reachable regardless of
        // whether this account already has a patient, not nested inside the
        // zero-patients branch only.
        var showJoinPatient by remember { mutableStateOf(false) }
        if (showJoinPatient) {
            JoinPatientScreen(patientRepository, onJoined = { showJoinPatient = false })
            return@VigilTheme
        }

        val patients by patientRepository.patientsForUser(uid).collectAsState(initial = null)

        when (val list = patients) {
            null -> LoadingScreen()
            else -> if (list.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    SetupScreen(patientRepository, uid) { /* patient list flow refreshes on write */ }
                    Column(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        TextButton(onClick = { showJoinPatient = true }) {
                            Text("Following a family member who already set this up?")
                        }
                        TextButton(onClick = { showIobSourceSetup = true }) {
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
                        onSignOut = authRepository::signOut,
                        onShowIobSourceSetup = { showIobSourceSetup = true }
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
    onSignOut: () -> Unit,
    onShowIobSourceSetup: () -> Unit
) {
    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
    val tabs = if (isOwner) Tab.entries else listOf(Tab.DASHBOARD, Tab.HISTORY, Tab.PREDICTIONS)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Image(
                        painter = painterResource(R.drawable.vigil_fox_mark),
                        contentDescription = "Vigil",
                        modifier = Modifier.height(36.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = onShowIobSourceSetup) {
                        Icon(Icons.Filled.Sync, contentDescription = "IOB source setup")
                    }
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
                Tab.SETTINGS -> {
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
                            PatientIdShareSection(patient.id, patient.displayName)
                            if (isOwner) {
                                OutlinedButton(
                                    onClick = { editingConnection = true },
                                    modifier = Modifier.fillMaxWidth().padding(24.dp, 16.dp, 24.dp, 0.dp)
                                ) {
                                    Text("Edit Gluroo connection")
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    AlertSettingsScreen(patientRepository, patient.id)
                                }
                            } else {
                                Text("Only the owner can edit thresholds", modifier = Modifier.padding(24.dp, 16.dp))
                            }
                        }
                    }
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

/** Surfaces the one identifier every self-service flow in this app (joining
 * as a follower, claiming IOB source) runs on, so a member actually has
 * somewhere to find it before texting it to whoever they want to grant
 * access to next. */
@Composable
private fun PatientIdShareSection(patientId: String, patientDisplayName: String) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(24.dp, 24.dp, 24.dp, 0.dp)) {
        Text("Patient ID (for $patientDisplayName)", style = MaterialTheme.typography.titleSmall)
        Text(
            "This identifies $patientDisplayName's record specifically - not " +
                "you, and not whoever you're about to send it to. Share it with " +
                "a family member's phone so they can follow $patientDisplayName, " +
                "or so a phone reporting IOB from $patientDisplayName's pump app " +
                "can be pointed at it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(patientId, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                clipboardManager.setText(AnnotatedString(patientId))
                copied = true
            }) {
                Text(if (copied) "Copied" else "Copy")
            }
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
