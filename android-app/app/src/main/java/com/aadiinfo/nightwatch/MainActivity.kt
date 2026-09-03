package com.aadiinfo.nightwatch

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aadiinfo.nightwatch.ui.VigilApp

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate() per the SplashScreen API
        // contract. Theme.Vigil.Launch (see themes.xml) configures what this
        // actually shows - a purpose-built high-res icon, not the OS's
        // stretched-blurry default derived from the adaptive launcher icon.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Without this, the splash dismisses as soon as the first frame is
        // ready - near-instant, not the deliberate brief flash of branding
        // it's meant to be. Held for a fixed window rather than gated on any
        // real readiness condition, since there's nothing else worth
        // blocking launch on.
        val splashStartMs = SystemClock.elapsedRealtime()
        splashScreen.setKeepOnScreenCondition {
            SystemClock.elapsedRealtime() - splashStartMs < SPLASH_MIN_DURATION_MS
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val container = (application as VigilApplication).container
        setContent {
            VigilApp(container.authRepository, container.patientRepository, container.fcmTokenRepository)
        }
    }

    private companion object {
        const val SPLASH_MIN_DURATION_MS = 1200L
    }
}
