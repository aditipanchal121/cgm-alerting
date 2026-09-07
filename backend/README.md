# Vigil backend (Firebase)

The always-on reliability core. Polls Gluroo on a fixed schedule
independent of any phone, evaluates alerts, and fans out to Firestore
history, push notifications, and any paired ESP32 alarm device.

## One-time setup

1. Install the Firebase CLI: `npm install -g firebase-tools`, then `firebase login`.
2. Create a Firebase project (console.firebase.google.com) with **Firestore**,
   **Realtime Database**, **Authentication** (enable Email/Password, or
   whichever sign-in method you want for owner + family accounts), and
   **Cloud Functions** (requires the Blaze pay-as-you-go plan - scheduled
   functions and outbound network calls to Gluroo need it; the free tier
   covers a single-family prototype's usage in practice).
3. Replace `REPLACE_WITH_YOUR_FIREBASE_PROJECT_ID` in `.firebaserc` with your
   project ID.
4. Grant the Cloud Functions service account (`<project-id>@appspot.gserviceaccount.com`
   by default) the **Secret Manager Admin** IAM role, so `savePatientCredentials`
   can create/update per-patient secrets and `pollGlucose` can read them.
5. Install dependencies: `cd functions && npm install`.
6. Set up the Python codebase's local virtualenv - unlike the TS side,
   `firebase deploy` does *not* create this for you (it expects one to
   already exist, the same way it expects `functions/node_modules` to
   already exist):
   ```
   cd functions-predict
   python -m venv venv
   venv/Scripts/pip install -r requirements.txt   # venv/bin/pip on macOS/Linux
   ```
7. One-time secret for the experimental-predictors function to authenticate
   itself - see "Experimental predictors" below:
   `firebase functions:secrets:set PREDICT_FUNCTION_SECRET`.
8. Deploy: `firebase deploy` (from `backend/`) - this deploys both Cloud
   Functions codebases (the TS `functions/` and the Python
   `functions-predict/`, see "Experimental predictors" below).

## Local development

`firebase emulators:start --only functions,firestore,database` runs
everything locally. The Secret Manager calls in `secretManager.ts` hit the
real cloud API even from the emulator (there's no local Secret Manager
emulator), so local testing still needs the IAM grant above and a real
project.

## Data model

See `firestore.rules` for the authoritative access model:
- `patients/{patientId}` - one per person being monitored; owner + follower
  members live in `patients/{patientId}/members/{uid}`.
- `patients/{patientId}/thresholds/current` - `PatientPhysiology`: insulin
  sensitivity factor and carb ratio. Shared across the whole family - every
  member can read it, but only the owner (the patient himself, who creates
  the record everyone else follows) can write it - unlike
  `members/{uid}/thresholds/current` below, since these are physiological
  facts about the patient rather than a personal alerting preference.
- `patients/{patientId}/members/{uid}/thresholds/current` - personal alert
  thresholds (low/high mg/dL, IOB threshold, night window, etc.) - each
  member sets their own.
- `patients/{patientId}/readings` - glucose/IOB/COB history, written only by
  `pollGlucose` via `persistNewReadings`, which persists every entry fetched
  since the patient doc's own `lastReadingMs` cursor (not just the newest),
  so a delayed/failed poll cycle doesn't silently drop a reading the way it
  used to. Each doc also carries the `insulinSensitivityFactor`/`carbRatio`
  that were in effect when it was written, so a later training join doesn't
  have to assume today's values applied historically. Kept for a full year
  (a deliberate training-data retention policy, not just an operational log)
  by the `cleanupOldReadings` scheduled function - storage cost is trivial
  at this volume (~15-16 MB/year/patient), this is about having an explicit,
  bounded policy rather than unbounded growth.
- `patients/{patientId}/alerts` - alert event log, written only by `pollGlucose`.
  A separate `cleanupOldAlerts` scheduled function deletes alerts older than
  3 days once a day, so this doesn't grow without bound.
- `patients/{patientId}/externalIob/current` - latest directly-reported IOB
  (e.g. Omnipod's own notification, read by a paired phone's
  NotificationListenerService - see `OmnipodIobListenerService.kt`),
  overwritten on every report. `pollGlucose` prefers this over Gluroo's own
  IOB whenever it's fresher than `EXTERNAL_IOB_FRESHNESS_MS`, and it's what
  gets baked into that poll's `readings` doc.
- `patients/{patientId}/externalIobHistory` - append-only log of every
  directly-reported IOB value (not just the latest), written alongside
  `externalIob/current` for the same reason `readings` is kept a full
  year: training data. To build a fully-aligned training set, join on
  nearest timestamp - e.g. per `readings.dateMs`, take the last
  `externalIobHistory.reportedAt <= dateMs` - rather than relying only on
  whatever `readings.iob` already captured at that poll tick.
- `patients/{patientId}/treatments` - bolus/carb event history mirrored from
  Nightscout's `treatments.json` (`{ nightscoutId, eventType, mills, insulin,
  carbs, durationMinutes, notes }`), written only by `pollGlucose` via
  `ingestNewTreatments`. Fetched and written incrementally via the patient
  doc's `lastTreatmentMs` cursor field. Training data, same as
  `readings`/`externalIobHistory`; also feeds the experimental predictors
  (see "Experimental predictors" below).
- `patients/{patientId}/livePredictions/current` - the experimental
  predictors' latest output, overwritten every poll cycle - the Android
  Predictions tab's only data source (a plain Firestore read, no on-device
  computation).
- `patients/{patientId}/predictionAccuracy` - append-only prediction history
  + a running per-predictor error summary, for later offline analysis. Not
  read by the app (see `firestore.rules`).
- `devices/{deviceId}` - ESP32 pairing: `{ patientId, pairedAt, pairedBy }`.
- Realtime Database `devices/{deviceId}/alert` - what the ESP32 firmware streams.

## Experimental predictors (`functions-predict/`)

A second, independently-deployed Cloud Functions codebase (Python 3.12 -
see `firebase.json`), separate from the TypeScript `functions/` codebase
above. `functions-predict/predictors.py` is the single source of truth for
5 experimental glucose predictors - no on-device (Kotlin) or TS copy exists
anymore. It's a pure module: no Firestore access, no Firebase imports at
all, just `readings + physiology + treatments -> projected values`, so it's
importable and testable with nothing but plain dicts/lists.
`functions-predict/main.py` is the only file that knows it's a Cloud
Function - a thin HTTP adapter, locked down via a shared secret
(`PREDICT_FUNCTION_SECRET`, Secret Manager-backed) that `pollGlucose` sends
as a header - not reachable without it, not even by signed-in app users.
(IAM invoker restriction would be the more idiomatic GCP-native mechanism,
but setting it needs a Cloud Run IAM permission this project's deploying
account doesn't have; the shared secret achieves the same property using
permissions already granted per the setup steps above.) One-time setup:
`firebase functions:secrets:set PREDICT_FUNCTION_SECRET` (prompts for a
value - any long random string).

Each poll cycle, `pollGlucose` calls this function once (passing the
readings/physiology/treatments it already fetched - no extra Firestore
reads for the inputs) and uses that one result for both `livePredictions`
and `predictionAccuracy` above - see `predictionAccuracy.ts`'s
`updateExperimentalPredictions`. Wrapped in a try/catch there, so a bug in
any of this can never affect real alerting or notifications.

The multi-bolus predictor's math - a standard exponential insulin/carb
activity curve, evaluated at the real elapsed time since each treatment
(see [LoopDocs' glucose prediction page](https://loopkit.github.io/loopdocs/operation/algorithm/prediction/)
for the curve's full derivation):

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
| `dose_i` | units of insulin in bolus `i` | `patients/{id}/treatments/{doc}.insulin` |
| `carbs_j` | grams of carbs in entry `j` | `patients/{id}/treatments/{doc}.carbs` |
| `mills_i`/`mills_j` | timestamp of the treatment | `patients/{id}/treatments/{doc}.mills` |
| `t_i`/`t_j` | minutes elapsed since the treatment | `(latestReading.dateMs - mills) / 60000`, recomputed every prediction cycle |
| `td`, `tp` (insulin) | duration of action / time to peak | fixed constants, `INSULIN_DURATION_MINUTES = 240`, `INSULIN_PEAK_MINUTES = 75`, shared across boluses |
| `td_j`, `tp_j` (carbs) | absorption duration / time to peak | `patients/{id}/treatments/{doc}.durationMinutes` if present (Nightscout's per-entry `absorptionTime`), else `CARB_DEFAULT_DURATION_MINUTES = 180`; `tp_j` is always `td_j * CARB_PEAK_RATIO` |
| `ISF` | insulin sensitivity factor (mg/dL lowered per unit) | `patients/{id}/thresholds/current.insulinSensitivityFactor` |
| `CSF` | carb sensitivity factor (mg/dL raised per gram) | derived as `ISF / carbRatio`, not independently measured/entered - see below |
| current glucose | most recent reading | the same `readings` array `pollGlucose` already fetched this cycle |
| `30` | prediction horizon, minutes | fixed - matches every other predictor here |

**Overlapping treatments are additive.** Each active bolus's `expectedDrop_i`
and each active carb entry's `expectedRise_j` is computed independently
against its own elapsed time, then summed. A correction bolus given 45
minutes after a meal bolus doesn't reset or interact with the first one's
curve; each contributes its own share of the total.

**CSF is derived, not measured.** There's no absolute way to measure "1 gram
of carbs raises glucose by X mg/dL" directly - different carb types (sugar
vs. starch, for example) can raise glucose differently, so this derivation
assumes uniform behavior across carb types as a simplification. What is
known is the insulin-to-carb ratio used for dosing (`carbRatio`), so
`CSF = ISF / carbRatio` is used instead - the standard clinical
relationship between the two ratios, not an independent estimate.

## Exporting aligned training data

`functions/src/scripts/exportTrainingData.ts` is a local-only script (not
deployed - it's never imported by `index.ts`) that pulls a patient's
`readings`, `treatments`, and `externalIobHistory`, aligns them into one CSV
row per reading (nearest-past IOB report, trailing insulin/carb totals), and
reports any gap in the readings sequence. Run from `functions/`:

```
npm run export-training-data -- <patientId>
```

Needs Application Default Credentials to reach Firestore locally - either
`gcloud auth application-default login` once, or a service account key JSON
(Firebase console > Project settings > Service accounts) via
`GOOGLE_APPLICATION_CREDENTIALS`. Output goes to `functions/training-data-export/`
(gitignored - this is real health data).

## Known prototype-level simplifications (call out before relying on this for real overnight monitoring)

- RTDB rules grant any signed-in user read/write to any device's alert node
  (`auth != null`, not scoped per-device). Fine for a single-family
  prototype; hardening for multi-family use would map custom auth claims to
  specific `deviceId`s.
- FCM token cleanup on delivery failure isn't implemented (see the TODO in
  `fcm.ts`) - stale tokens just get retried and fail silently.
- `pollGlucose` runs every 5 minutes on a fixed schedule; Gluroo/CGM data
  itself typically updates every 5 minutes, so this is close to the
  practical ceiling for polling-based freshness.
