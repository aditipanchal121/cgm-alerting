package com.aadiinfo.nightwatch.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aadiinfo.nightwatch.R
import com.aadiinfo.nightwatch.data.repository.AuthRepository
import com.aadiinfo.nightwatch.data.repository.FcmTokenRepository
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.Patient
import com.aadiinfo.nightwatch.ui.alertsettings.AlertSettingsScreen
import com.aadiinfo.nightwatch.ui.auth.LoginScreen
import com.aadiinfo.nightwatch.ui.dashboard.DashboardScreen
import com.aadiinfo.nightwatch.ui.history.HistoryScreen
import com.aadiinfo.nightwatch.ui.connect.ConnectToPatientScreen
import com.aadiinfo.nightwatch.ui.mcupairing.McuPairingScreen
import com.aadiinfo.nightwatch.ui.predictions.PredictionsScreen
import com.aadiinfo.nightwatch.ui.setup.SetupScreen
import com.aadiinfo.nightwatch.ui.theme.VigilTheme
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.messaging.messaging
import kotlinx.coroutines.launch
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
        // Tags crash reports with which of the (currently 4 known) family
        // accounts hit them - with so few users, "who saw this" is often the
        // fastest way to reproduce it.
        LaunchedEffect(uid) { Firebase.crashlytics.setUserId(uid) }

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
        // link below for before one does. Handles both "follow a family
        // member" and "this phone also reports IOB" in one flow, entering
        // the patient ID only once - see ConnectToPatientScreen's doc
        // comment for why that used to be two separate screens/fields.
        var showConnect by remember { mutableStateOf(false) }
        if (showConnect) {
            ConnectToPatientScreen(
                patientRepository,
                onBack = { showConnect = false },
                onDone = { showConnect = false }
            )
            return@VigilTheme
        }

        val patients by patientRepository.patientsForUser(uid).collectAsState(initial = null)

        when (val list = patients) {
            null -> LoadingScreen()
            else -> if (list.isEmpty()) {
                // "Join" is the default/primary choice, not "create" - most new
                // accounts are a family member attaching to a profile someone
                // else already set up, and showing a create form front-and-
                // center (with join as a small link underneath, as this used
                // to) made it easy to create a redundant second profile by
                // mistake instead of noticing the link - which is exactly what
                // produced the orphan-profile bug this screen now prevents.
                var showCreateProfile by remember { mutableStateOf(false) }
                if (showCreateProfile) {
                    SetupScreen(patientRepository, uid) { /* patient list flow refreshes on write */ }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Welcome to Vigil", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { showConnect = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("I have a Profile ID to follow")
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showCreateProfile = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("I am the CGM / pump user")
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
                        uid = uid,
                        isOwner = patient.ownerUid == uid,
                        onSignOut = authRepository::signOut,
                        onShowConnect = { showConnect = true }
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

/** Shrinks in 1sp steps until the label fits its available width on one
 * line, instead of ellipsis-truncating - the compose-bom version this app
 * pins (2024.09.00) predates Text's built-in autoSize parameter, so this is
 * the manual equivalent: render, check onTextLayout for overflow, retry one
 * size down. Bottomed out at 8sp so a pathologically narrow width can't
 * shrink this into something unreadable instead of just clipping. */
@Composable
private fun AutoSizeTabLabel(text: String) {
    val startingSize = MaterialTheme.typography.labelSmall.fontSize
    var fontSize by remember(text) { mutableStateOf(startingSize) }
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        fontSize = fontSize,
        onTextLayout = { result ->
            if (result.didOverflowWidth && fontSize.value > 8f) {
                fontSize = (fontSize.value - 1f).sp
            }
        }
    )
}

private enum class Tab(val label: String) {
    DASHBOARD("Home"),
    HISTORY("History"),
    // "Predictions" was the longest label, most prone to truncating on a
    // narrower screen or larger system font size once all 5 tabs became
    // visible to everyone - shortened rather than relying only on ellipsis.
    PREDICTIONS("Predict"),
    SETTINGS("Settings"),
    PAIR_DEVICE("Alarm")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    patientRepository: PatientRepository,
    patient: Patient,
    uid: String,
    isOwner: Boolean,
    onSignOut: () -> Unit,
    onShowConnect: () -> Unit
) {
    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
    // Shown to everyone regardless of role - alert thresholds are personal to
    // each member now, and the two remaining owner-only actions (edit Gluroo
    // connection, pair an alarm device) each gate themselves internally with
    // their own "Only the owner can..." fallback. Hiding the tabs entirely
    // for followers used to also hide those fallbacks, along with read-only
    // content every member
    // should be able to reach - the Profile ID share section in particular,
    // which a follower needs in order to pass it on to another family member.
    val tabs = Tab.entries

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
                    IconButton(onClick = onShowConnect) {
                        Icon(Icons.Filled.Sync, contentDescription = "Connect to a family member")
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
                        // it - force single-line. Shrinking to fit (below)
                        // instead of a fixed size handles a larger system font
                        // setting directly rather than just buying headroom
                        // against it - "Dashboard" still didn't fit on labelSmall
                        // alone on a phone with a larger text-size setting.
                        label = { AutoSizeTabLabel(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.DASHBOARD -> DashboardScreen(patientRepository, patient.id, uid)
                Tab.HISTORY -> HistoryScreen(patientRepository, patient.id, uid)
                Tab.PREDICTIONS -> PredictionsScreen(patientRepository, patient.id, uid)
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
                            } else {
                                // The owner can't leave their own patient (no one
                                // would be left to own it) - this is only ever
                                // reachable for a follower, e.g. undoing a
                                // mistaken connection to the wrong Profile ID.
                                LeavePatientButton(patientRepository, patient.id, patient.displayName)
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                AlertSettingsScreen(patientRepository, patient.id, uid, isOwner)
                            }
                        }
                    }
                }
                // Any member can pair their own alarm device now, not just the
                // owner - see pairMcuDevice: each device buzzes off the alert
                // thresholds of whoever paired it, so this needs to be
                // reachable by whoever actually owns that physical ESP32.
                Tab.PAIR_DEVICE -> McuPairingScreen(patientRepository, patient.id)
            }
        }
    }
}

/** Self-service counterpart to the Connect screen's join flow - lets a
 * follower undo a mistaken connection without needing it fixed by hand.
 * Confirms first since this is a real access change, not just a display
 * preference: leaving means re-entering the Profile ID to reconnect. */
@Composable
private fun LeavePatientButton(patientRepository: PatientRepository, patientId: String, patientDisplayName: String) {
    var showConfirm by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.padding(24.dp, 16.dp, 24.dp, 0.dp)) {
        OutlinedButton(
            onClick = { showConfirm = true },
            enabled = !leaving,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (leaving) "Leaving..." else "Stop following $patientDisplayName")
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Stop following $patientDisplayName?") },
            text = {
                Text(
                    "You'll lose access to their Dashboard, History, and alerts. " +
                        "You'd need the Profile ID again to reconnect."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    leaving = true
                    error = null
                    scope.launch {
                        runCatching { patientRepository.leavePatient(patientId) }
                            .onFailure {
                                leaving = false
                                error = it.message ?: "Could not leave - try again."
                            }
                        // On success, deliberately left leaving=true and no
                        // further state change here - patientsForUser's live
                        // listener drops this patient from the list on its
                        // own, which re-renders this whole screen away.
                    }
                }) {
                    Text("Stop following", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
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
        Text("Profile ID (for $patientDisplayName)", style = MaterialTheme.typography.titleSmall)
        Text(
            "This identifies $patientDisplayName's profile specifically. Share it " +
                "with a family member's phone so they can follow $patientDisplayName, " +
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
