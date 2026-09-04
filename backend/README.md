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
6. Deploy: `firebase deploy` (from `backend/`).

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
  `readings`/`externalIobHistory`; also consumed on-device by
  `GlucosePredictor.kt`'s `MultiBolusInsulinActivityPredictor` (see
  `android-app/README.md`).
- `devices/{deviceId}` - ESP32 pairing: `{ patientId, pairedAt, pairedBy }`.
- Realtime Database `devices/{deviceId}/alert` - what the ESP32 firmware streams.

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
