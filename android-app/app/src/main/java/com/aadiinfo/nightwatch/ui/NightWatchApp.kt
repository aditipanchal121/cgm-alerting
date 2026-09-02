package com.aadiinfo.nightwatch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aadiinfo.nightwatch.data.repository.AuthRepository
import com.aadiinfo.nightwatch.data.repository.FcmTokenRepository
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.Patient
import com.aadiinfo.nightwatch.ui.alertsettings.AlertSettingsScreen
import com.aadiinfo.nightwatch.ui.auth.LoginScreen
import com.aadiinfo.nightwatch.ui.dashboard.DashboardScreen
import com.aadiinfo.nightwatch.ui.history.HistoryScreen
import com.aadiinfo.nightwatch.ui.mcupairing.McuPairingScreen
import com.aadiinfo.nightwatch.ui.setup.SetupScreen
import com.aadiinfo.nightwatch.ui.theme.NightWatchTheme
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging
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
                SetupScreen(patientRepository, uid) { /* patient list flow refreshes on write */ }
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
    SETTINGS("Settings"),
    PAIR_DEVICE("Alarm device")
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
    val tabs = if (isOwner) Tab.entries else listOf(Tab.DASHBOARD, Tab.HISTORY)

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
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.DASHBOARD -> DashboardScreen(patientRepository, patient.id)
                Tab.HISTORY -> HistoryScreen(patientRepository, patient.id)
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

private fun tabIcon(tab: Tab) = when (tab) {
    Tab.DASHBOARD -> Icons.Filled.Favorite
    Tab.HISTORY -> Icons.Filled.History
    Tab.SETTINGS -> Icons.Filled.Settings
    Tab.PAIR_DEVICE -> Icons.Filled.Bluetooth
}
