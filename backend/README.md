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
- `patients/{patientId}/thresholds/current` - alert thresholds, owner-write only.
- `patients/{patientId}/readings` - glucose/IOB/COB history, written only by
  `pollGlucose`. Kept for a full year (a deliberate training-data retention
  policy, not just an operational log) by the `cleanupOldReadings` scheduled
  function - storage cost is trivial at this volume (~15-16 MB/year/patient),
  this is about having an explicit, bounded policy rather than unbounded growth.
- `patients/{patientId}/alerts` - alert event log, written only by `pollGlucose`.
  A separate `cleanupOldAlerts` scheduled function deletes alerts older than
  3 days once a day, so this doesn't grow without bound.
- `devices/{deviceId}` - ESP32 pairing: `{ patientId, pairedAt, pairedBy }`.
- Realtime Database `devices/{deviceId}/alert` - what the ESP32 firmware streams.

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
