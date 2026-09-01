# NightWatch Android app

Kotlin + Jetpack Compose client for the Firebase backend in `../backend`.
The backend does the reliability-critical polling of Gluroo; this app is a
thin Firebase client (Auth, Firestore listeners, FCM) plus the on-device
pieces that only a phone can do: a full-screen critical-alert activity that
wakes/interrupts like an alarm clock, and pairing an ESP32 haptic alarm
device.

## One-time setup

1. In your Firebase project console, add an Android app with package name
   `com.aadiinfo.nightwatch` (or change `applicationId`/`namespace` in
   `app/build.gradle.kts` and this instruction together).
2. Download the generated `google-services.json` and place it at
   `app/google-services.json` (gitignored - never commit it).
3. Open this `android-app/` folder in Android Studio. It will offer to
   generate the Gradle wrapper jar (this repo ships
   `gradle/wrapper/gradle-wrapper.properties` pointing at Gradle 8.7, but not
   the wrapper jar binary itself) - accept that, or run `gradle wrapper` once
   if you have a local Gradle install.
4. Build/run on a device or emulator running API 26+.

## Notes

- No foreground service, exact alarms, or boot receiver: the backend's
  `pollGlucose` function is what actually needs to survive the phone being
  off, so this app doesn't need any of that just to stay "reliable" - it's
  a straightforward Firebase-listening client.
- `notifications/AlarmActivity.kt` is the "wake the user" groundwork:
  full-screen, shows over the lock screen, loops an alarm-usage sound and
  vibration until dismissed. It's launched both via a full-screen-intent
  notification and directly from `NightWatchFcmService`, since some OEM
  Android skins don't reliably honor full-screen intents from a
  background-posted notification alone.
- `ui/mcupairing/McuPairingScreen.kt` pairs an ESP32 (see `../firmware`) by
  device ID - the phone doesn't relay alerts to it, both the phone and the
  ESP32 receive alerts independently from the backend.
