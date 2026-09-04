# Vigil Android app

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
  notification and directly from `VigilFcmService`, since some OEM
  Android skins don't reliably honor full-screen intents from a
  background-posted notification alone.
- `ui/mcupairing/McuPairingScreen.kt` pairs an ESP32 (see `../firmware`) by
  device ID - the phone doesn't relay alerts to it, both the phone and the
  ESP32 receive alerts independently from the backend.

## Multi-bolus insulin activity predictor (experimental)

`domain/GlucosePredictor.kt`'s `MultiBolusInsulinActivityPredictor` is one of
several predictors on the Predictions (beta) tab; it does not drive real
alerting (`backend/functions/src/alertEngine.ts` does that, independently).
It reads actual bolus and carb history and evaluates a standard exponential
activity curve at the real elapsed time since each one - see the "Model
details" link on this predictor's info dialog in-app, or
[LoopDocs' insulin modeling page](https://loopkit.github.io/loopdocs/operation/algorithm/insulin-modeling/)
directly, for the curve's full derivation:

```
Frac(t, td, tp) = 1 - S(1-a)( (t^2/(tau*td*(1-a)) - t/tau - 1)e^(-t/tau) + 1 )
tau = tp(1 - tp/td) / (1 - 2tp/td)
a = 2*tau/td
S = 1 / (1 - a + (1+a)e^(-td/tau))

expectedDrop_i = dose_i * (Frac(t_i, 240, 75) - Frac(t_i + 30, 240, 75)) * ISF
expectedRise_j = carbs_j * (Frac(t_j, td_j, tp_j) - Frac(t_j + 30, td_j, tp_j)) * CSF
netDrop = sum(expectedDrop_i for each active bolus i) - sum(expectedRise_j for each active carb entry j)
projected = currentGlucose - netDrop
```

Where each value comes from:

| Symbol | Meaning | Source |
|---|---|---|
| `dose_i` | units of insulin in bolus `i` | `patients/{id}/treatments/{doc}.insulin`, fetched via `observeRecentTreatments` |
| `carbs_j` | grams of carbs in entry `j` | `patients/{id}/treatments/{doc}.carbs` |
| `mills_i`/`mills_j` | timestamp of the treatment | `patients/{id}/treatments/{doc}.mills` |
| `t_i`/`t_j` | minutes elapsed since the treatment | `(latestReading.dateMs - mills) / 60000`, recomputed every prediction cycle |
| `td`, `tp` (insulin) | duration of action / time to peak | fixed constants, `INSULIN_DURATION_MINUTES = 240`, `INSULIN_PEAK_MINUTES = 75`, shared across boluses |
| `td_j`, `tp_j` (carbs) | absorption duration / time to peak | `patients/{id}/treatments/{doc}.durationMinutes` if present (Nightscout's per-entry `absorptionTime`), else `CARB_DEFAULT_DURATION_MINUTES = 180`; `tp_j` is always `td_j * CARB_PEAK_RATIO` |
| `ISF` | insulin sensitivity factor (mg/dL lowered per unit) | `Thresholds.insulinSensitivityFactor`, the same per-member value `IobAwarePredictor` and `AlertSettingsScreen` use |
| `CSF` | carb sensitivity factor (mg/dL raised per gram) | derived as `ISF / Thresholds.carbRatio`, not independently measured/entered |
| current glucose | most recent reading | `patients/{id}/readings`, via `observeRecentReadings` |
| `30` | prediction horizon, minutes | `horizonMinutes` parameter, same as every other predictor in this file |

**Overlapping treatments are additive.** Each active bolus's `expectedDrop_i`
and each active carb entry's `expectedRise_j` is computed independently
against its own elapsed time, then summed. A correction bolus given 45
minutes after a meal bolus doesn't reset or interact with the first one's
curve; each contributes its own share of the total.

**CSF is derived, not measured.** There's no absolute way to measure "1 gram
of carbs raises glucose by X mg/dL" directly - different carb types (sugar
vs. starch, for example) can raise glucose differently, so this derivation
assumes uniform behavior across carb types as a simplification. What is
known is the insulin-to-carb ratio used for dosing (`Thresholds.carbRatio`),
so `CSF = ISF / carbRatio` is used instead - the standard clinical
relationship between the two ratios, not an independent estimate.