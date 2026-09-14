# Vigil

Nighttime glucose monitoring and alerting, built on Gluroo's
Nightscout-compatible API (Gluroo Global Connect). Three cooperating
pieces, each with its own setup instructions:

- **`backend/`** - Firebase (Cloud Functions + Firestore + Auth + FCM +
  Realtime Database). The reliability core: polls Gluroo on a schedule
  independent of any phone, evaluates alert thresholds/trend/IOB, and fans
  out to push notifications, alert history, and any paired haptic alarm
  device. Family members get read-only "follower" access through the same
  backend. Start here - nothing else works without it deployed.
- **`android-app/`** - Kotlin + Jetpack Compose. A Firebase client with a
  full-screen, alarm-clock-style critical alert (bypasses silent mode,
  shows over the lock screen) and a screen to pair the ESP32 device below.
- **`firmware/`** - ESP32 (PlatformIO/C++). A dedicated vibrating alarm
  device, purpose-built for stronger haptic feedback than a phone can give -
  connects to WiFi directly and gets alerts from the backend independent of
  the phone.
- **`ml/`** - Python. Plan + framework for fine-tuning a time-series
  foundation model (Chronos) on CGM data, offline and local-only - no
  Firebase access, no training runs started yet. See `ml/README.md`.

## Suggested setup order

1. `backend/` - create the Firebase project, deploy Cloud Functions and
   security rules. See `backend/README.md`.
2. `android-app/` - point it at the same Firebase project, sign up, create
   a patient, and enter/verify your real Gluroo Global Connect URL + API
   secret. See `android-app/README.md`.
3. `firmware/` - flash an ESP32, connect it to WiFi, and pair it from the
   app. See `firmware/README.md`.

## Deferred (see the plan for the reasoning)

- A Wear OS app - the `HapticAlertDispatcher` extension point in the
  Android app is ready for it, but the ESP32 device was prioritized first.
- ML models beyond the linear-extrapolation baseline predictor - both the
  backend (`backend/functions/src/predictor.ts`) and the Android app
  (`android-app/.../domain/GlucosePredictor.kt`) isolate prediction behind
  an interface specifically so new models can be swapped in and A/B tested
  against this baseline later.
- A separate web dashboard for family - v1 family/follower access is
  through the same Android app.

## A note on safety

Gluroo itself is not FDA-cleared and its own docs say dosing decisions
shouldn't be based on it. This project is a monitoring/alerting aid on top
of that data, not a substitute for it - treat it the same way: useful
signal, not a medical device, and don't let it replace whatever monitoring
setup already exists while you're validating that alerts actually fire
reliably.
