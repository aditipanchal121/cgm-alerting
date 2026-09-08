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
- `patients/{patientId}/predictionAccuracy` - append-only, one doc per
  prediction cycle (`{dateMs}`), keyed and structured so later offline
  analysis (MAE, RMSE, directional accuracy, full-vs-degraded-data splits,
  or anything else - see `scripts/exportTrainingData.ts` for the existing
  pattern of pulling this kind of collection into a CSV) never needs to join
  across collections - everything about a given prediction lives on its one
  doc: `predictions` (per predictor key), `notes` (each predictor's `note`
  at prediction time - null means it had full data that cycle, non-null
  names what was missing, e.g. multiBolus's "No reliable COB history"),
  `startSgv` (glucose at prediction time), and once resolved
  ~30 minutes later, `actualSgv` and `errors` (per predictor,
  `predicted - actualSgv`). Also a running per-predictor summary at
  `predictionAccuracy/summary` (`count`, `sumAbsError`, `sumError` -
  MAE/mean bias, `FieldValue.increment`-only, no extra reads) for a quick
  live sanity check, not a replacement for the fuller offline analysis the
  per-doc fields above are for. For later analysis only - not read by the
  app (see `firestore.rules`).
- `patients/{patientId}/iobCobHistory/recent` - a rolling window of the last
  15 `{dateMs, iob, cob}` points, built by `pollGlucose` itself (see
  "Experimental predictors" below for why) since Gluroo's own devicestatus
  feed can't supply real history. Used only by `predict_multi_bolus`. Not
  read by the app.
- `devices/{deviceId}` - ESP32 pairing: `{ patientId, pairedAt, pairedBy }`.
- Realtime Database `devices/{deviceId}/alert` - what the ESP32 firmware streams.

## Experimental predictors (`functions-predict/`)

A second, independently-deployed Cloud Functions codebase (Python 3.12 -
see `firebase.json`), separate from the TypeScript `functions/` codebase
above. `functions-predict/predictors.py` is the single source of truth for
5 experimental glucose predictors - no on-device (Kotlin) or TS copy exists
anymore. It's a pure module: no Firestore access, no Firebase imports at
all, just `readings + physiology + iobCobHistory -> projected values`, so
it's importable and testable with nothing but plain dicts/lists.
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
readings/physiology it already fetched, plus `iobCobHistory` - see below)
and uses that one result for both `livePredictions` and `predictionAccuracy`
above - see `predictionAccuracy.ts`'s `updateExperimentalPredictions`.
Wrapped in a try/catch there, so a bug in any of this can never affect real
alerting or notifications. (An earlier version of this also passed
`treatments` - removed once nothing actually read it anymore, see the
multi-bolus predictor's math below.)

**`iobCobHistory` is a separate input from `readings`, built ourselves in
Firestore.** `readings` is `pollGlucose`'s live per-cycle CGM fetch, and
only its single newest entry ever carries a real `iob`/`cob` value - fine
for the other 4 predictors, but `predict_multi_bolus` needs a *second*,
older point to compute a trend. Nightscout's `devicestatus.json` can't
supply that: confirmed against this account, it only ever returns a single
current snapshot regardless of `count` requested, with no `mills`/
`created_at` field to even timestamp it by. So `updateIobCobHistory` in
`index.ts` maintains a small rolling window itself instead -
`patients/{id}/iobCobHistory/recent` (`{ points: [{dateMs, iob, cob}, ...] }`,
capped at 15) - read, appended with this cycle's point, trimmed, and
written back once per cycle (1 read + 1 write, ~288/day each; skipped
entirely on a cycle where both iob and cob are null, so nothing gets
written when there's nothing worth recording). The *current* IOB/COB value
passed to the predictor always comes from `readings` (this cycle's freshest
value), never from whatever the history's own newest point happens to be -
see `predict_multi_bolus`'s `_iob_cob_working_set` - so a stale history
read can't make "now" look staler than it is; it can only fail to find an
older point, in which case that term is skipped for the cycle rather than
guessed at.

**The multi-bolus predictor's math** (`predict_multi_bolus`, renamed "IOB/COB
decay" in the UI) - both insulin and carb effects are estimated the same
way, by reading the recent slope of the patient's actual reported IOB/COB
and extrapolating it forward, rather than reconstructing either from
individual bolus/carb treatment records:

```
ΔIOB(30) = IOB_now - max(0, IOB_now + slope_IOB * 30)
ΔCOB(30) = COB_now - max(0, COB_now + slope_COB * 30)
netDrop = (ISF * ΔIOB(30)) - (CSF * ΔCOB(30))
projected = currentGlucose - netDrop
```

Where `slope_IOB`/`slope_COB` are each `min(0, (value_b - value_a) /
minutes_between(a, b))` for the two most useful eligible points found in
`iobCobHistory` (preferring one from within the last 30 minutes, falling
back further back if that's not available) - each `cob` value is rounded to
a whole gram before use, since Gluroo reports more decimal precision than
real carb-entry granularity has, and that fake precision was making the
slope noisier than the underlying signal. The `min(0, ...)` clamp matters:
a *rising* IOB/COB means a dose/meal was just logged, not decay in
progress, and naively extrapolating that rise forward would invert the
predicted effect's sign (a meal just entered would wrongly predict glucose
*falling*). A rising value is instead treated as no expected change this
cycle - the following cycles pick up the real decay once it starts.

This predictor originally summed a standard exponential insulin/carb
activity curve (see
[LoopDocs' glucose prediction page](https://loopkit.github.io/loopdocs/operation/algorithm/prediction/))
over each active `treatments` entry - but that assumed `treatments` was a
complete ledger, which for this patient it isn't: Gluroo's feed only
reports `"Correction Bolus"` events, so real meal boluses (and carbs) never
show up in it, and the curve-based sum was silently underestimating both
effects. The trade-off of the slope-based approach: it's a local linear
approximation of each value's true decay curve (which ramps toward a peak,
then tails off), not the curve itself - reasonable given the 30-minute
horizon is short relative to either curve's full duration, but it won't
capture curvature right around a peak the way the full curve would.

**IOB and COB aren't equally trustworthy.** IOB is sourced from the pump's
own notification (see `externalIob/current` above), independent of Gluroo.
COB still comes from Gluroo's own `devicestatus.json` feed
(`glurooCob`/`loop.cob`/`openaps.cob` - see `fetchDeviceStatus` in
`nightscout.ts`) - the same pipeline whose carb logging is already known to
be incomplete for this patient, so it isn't guaranteed to be any more
reliable, just differently sourced. Each term (insulin, carbs) is skipped
independently whenever its own current value or slope isn't available -
never backed by a stale or partial guess - and the predictor's `note` field
says which, if either, was skipped that cycle (recorded on the
`predictionAccuracy` doc above so a full-vs-degraded split can be
reconstructed offline later).

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
