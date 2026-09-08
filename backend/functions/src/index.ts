import { onSchedule } from 'firebase-functions/v2/scheduler';
import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { setGlobalOptions } from 'firebase-functions/v2';
import * as admin from 'firebase-admin';
import { fetchRecentReadings, fetchTreatmentsSince, verifyConnection, DeviceStatusPoint } from './nightscout';
import { evaluateAlerts } from './alertEngine';
import { sendAlertPush, sendReadingStatusPush, getMemberTokens, getMemberTokensByUid } from './fcm';
import { getPatientSecret, storePatientSecret } from './secretManager';
import { deleteOldAlerts, deleteOldReadings, deleteOldPredictionAccuracy } from './cleanup';
import { getExternalIob } from './externalIob';
import { updateExperimentalPredictions } from './predictionAccuracy';
import { PREDICT_FUNCTION_SECRET } from './experimentalPredictors';
import { AlertEvent, GlucoseReading, PatientPhysiology, Thresholds } from './types';

admin.initializeApp();
setGlobalOptions({ region: 'us-central1', maxInstances: 10 });

let _db: admin.firestore.Firestore | undefined;
function db(): admin.firestore.Firestore {
  if (!_db) _db = admin.firestore();
  return _db;
}

let _rtdb: admin.database.Database | undefined;
function rtdb(): admin.database.Database {
  if (!_rtdb) _rtdb = admin.database();
  return _rtdb;
}

const projectId = process.env.GCLOUD_PROJECT!;

// Off while testing the externalIob override path - flip back to true once
// external reporting is confirmed reliable.
const GLUROO_IOB_GLITCH_DETECTION_ENABLED = false;

const DEFAULT_THRESHOLDS: Thresholds = {
  units: 'mgdl',
  lowMgdl: 80,
  urgentLowMgdl: 65,
  highMgdl: 200,
  urgentHighMgdl: 260,
  iobThreshold: 8,
  nightWindowStart: '22:00',
  nightWindowEnd: '07:00',
  timezone: 'America/Los_Angeles',
  staleMinutes: 20,
  // PREDICTED_LOW excluded by default - it's a linear-extrapolation guess
  // (see predictor.ts), off until a member explicitly turns it on knowing
  // that. Every other type defaults on, matching prior behavior.
  enabledAlertTypes: [
    'LOW',
    'URGENT_LOW',
    'HIGH',
    'URGENT_HIGH',
    'IOB_HIGH',
    'IOB_UNRELIABLE',
    'STALE_DATA',
    'COMPRESSION_LOW',
  ],
};

const DEFAULT_PHYSIOLOGY: PatientPhysiology = {
  insulinSensitivityFactor: 40,
  carbRatio: 10,
};

// Polls every registered patient's Gluroo data on a fixed schedule, evaluates
// alerts, fans out to Firestore history, FCM push, and any paired ESP32.
export const pollGlucose = onSchedule(
  { schedule: 'every 5 minutes', secrets: [PREDICT_FUNCTION_SECRET] },
  async () => {
    const patientsSnap = await db().collection('patients').get();
    await Promise.all(patientsSnap.docs.map((doc) => pollOnePatient(doc.id, doc.data())));
  }
);

export const cleanupOldAlerts = onSchedule('every 24 hours', async () => {
  const deleted = await deleteOldAlerts(db());
  console.log(`cleanupOldAlerts: deleted ${deleted} alert(s) older than the retention window`);
});

// Kept a full year (see cleanup.ts) as training data.
export const cleanupOldReadings = onSchedule('every 24 hours', async () => {
  const deleted = await deleteOldReadings(db());
  console.log(`cleanupOldReadings: deleted ${deleted} reading(s) older than the retention window`);
});

export const cleanupOldPredictionAccuracy = onSchedule('every 24 hours', async () => {
  const deleted = await deleteOldPredictionAccuracy(db());
  console.log(`cleanupOldPredictionAccuracy: deleted ${deleted} doc(s) older than the retention window`);
});

async function pollOnePatient(patientId: string, patient: FirebaseFirestore.DocumentData): Promise<void> {
  try {
    // Fetched once and reused below (sendReadingStatusPush, the per-member
    // alert loop, sendAlertPush) rather than re-querying members/tokens per use.
    const membersSnap = await db().collection('patients').doc(patientId).collection('members').get();
    const uids = membersSnap.docs.map((d) => d.id);
    const tokensByUid = await getMemberTokensByUid(uids);
    const allTokens = [...tokensByUid.values()].flat();

    const apiSecret = await getPatientSecret(projectId, patientId);
    const readings = await fetchRecentReadings(patient.nightscoutUrl, apiSecret, 6);
    if (!readings.length) {
      // No sensor data at all (e.g. a brand-new setup) - still push so the
      // persistent notification exists and says so, rather than never
      // appearing or freezing on stale content forever.
      await sendReadingStatusPush(patientId, patient.displayName ?? 'Vigil', null, allTokens);
      return;
    }

    const nowMs = Date.now();
    const latest = readings[readings.length - 1];
    console.log(
      `pollOnePatient(${patientId}): latest reading dateMs=${latest.dateMs} ` +
        `(${new Date(latest.dateMs).toISOString()}, ${Math.round((nowMs - latest.dateMs) / 60000)} min old) sgv=${latest.sgv}`
    );

    // Gluroo's devicestatus feed has occasionally reset IOB to exactly 0 from
    // a much higher value within one poll, which isn't physiologically
    // plausible. Flagged (not hidden) as iobUnreliable, using the last known
    // *non-zero* IOB rather than the previous reading, since a glitch can
    // produce several consecutive zero polls the flag needs to stay up through.
    const externalIob = await getExternalIob(db(), patientId, nowMs);
    if (externalIob !== null) {
      // Directly-reported (e.g. a paired phone's NotificationListenerService)
      // - preferred over Gluroo's field once any report has ever landed,
      // flagged unreliable rather than falling back once it goes stale,
      // since Gluroo's own field is frequently absent for this account.
      latest.iob = externalIob.iob;
      latest.iobUnreliable = !externalIob.fresh;
    } else if (GLUROO_IOB_GLITCH_DETECTION_ENABLED && latest.iob === 0) {
      const lastNonZeroIob = typeof patient.lastNonZeroIob === 'number' ? patient.lastNonZeroIob : null;
      latest.iobUnreliable = lastNonZeroIob !== null && lastNonZeroIob > 0.5;
    } else if (GLUROO_IOB_GLITCH_DETECTION_ENABLED && latest.iob !== null) {
      await db().collection('patients').doc(patientId).update({ lastNonZeroIob: latest.iob });
    }

    await writeCurrentReading(patientId, latest);

    const physiology = await getPhysiology(patientId);
    await persistNewReadings(patientId, patient, readings, nowMs, physiology);
    await ingestNewTreatments(patientId, patient, apiSecret);

    await sendReadingStatusPush(patientId, patient.displayName ?? 'Vigil', latest, allTokens);

    // Experimental - see predictionAccuracy.ts. Wrapped so a bug here can
    // never affect real alerting/notifications above.
    try {
      const iobCobHistory = await updateIobCobHistory(patientId, {
        dateMs: latest.dateMs,
        iob: latest.iob,
        cob: latest.cob,
      });
      await updateExperimentalPredictions(db(), patientId, readings, physiology, iobCobHistory, nowMs);
    } catch (err) {
      console.error(`updateExperimentalPredictions failed for patient ${patientId}`, err);
    }

    // Evaluated per member - each has their own thresholds, so the same
    // reading can cross one person's limits and not another's. eventsByUid
    // feeds updatePairedDevices below, so each ESP32 buzzes per whoever paired it.
    const eventsByUid = new Map<string, AlertEvent[]>();

    for (const memberDoc of membersSnap.docs) {
      const uid = memberDoc.id;
      const thresholds: Thresholds = { ...DEFAULT_THRESHOLDS, ...(memberDoc.data().thresholds ?? {}) };

      const events = evaluateAlerts(readings, thresholds, nowMs);
      eventsByUid.set(uid, events);

      for (const event of events) {
        await db()
          .collection('patients')
          .doc(patientId)
          .collection('members')
          .doc(uid)
          .collection('alerts')
          .add(event);
        if (event.severity !== 'INFO') {
          await sendAlertPush(uid, patientId, patient.displayName ?? 'Vigil', event, tokensByUid.get(uid) ?? []);
        }
      }
    }

    await updatePairedDevices(patientId, eventsByUid, nowMs);
  } catch (err) {
    console.error(`pollGlucose failed for patient ${patientId}`, err);
    // Nightscout itself may be unreachable this cycle - still push so the
    // persistent notification doesn't silently freeze on stale content
    // instead of reflecting that nothing new is available. Uses the
    // self-contained getMemberTokens (its own fresh query) rather than
    // this cycle's tokensByUid above, since the failure that landed here
    // could have happened before that was ever fetched.
    const fallbackTokens = await getMemberTokens(patientId).catch(() => [] as string[]);
    await sendReadingStatusPush(patientId, patient.displayName ?? 'Vigil', null, fallbackTokens).catch((pushErr) =>
      console.error(`sendReadingStatusPush(${patientId}) fallback also failed`, pushErr)
    );
  }
}

// Overwritten every cycle regardless of whether this reading is new -
// the dashboard's single source of truth for "latest reading", so it can
// never diverge from what sendReadingStatusPush pushes to the notification
// (same object, same cycle).
async function writeCurrentReading(patientId: string, reading: GlucoseReading): Promise<void> {
  await db().collection('patients').doc(patientId).collection('liveReading').doc('current').set(reading);
}

/** Persists every fetched entry newer than lastReadingMs, not just the
 * newest - so a delayed/missed poll cycle catches up on entries it would
 * otherwise permanently drop, and skips writing a duplicate when Nightscout
 * hasn't posted anything new. Backfilled (non-newest) entries don't get the
 * live externalIob/glitch-detection treatment - externalIobHistory is the
 * accurate source for reconstructing historical IOB at those timestamps. */
async function persistNewReadings(
  patientId: string,
  patient: FirebaseFirestore.DocumentData,
  readings: GlucoseReading[],
  nowMs: number,
  physiology: PatientPhysiology
): Promise<void> {
  const lastReadingMs = typeof patient.lastReadingMs === 'number' ? patient.lastReadingMs : null;
  const newReadings = readings.filter((r) => lastReadingMs === null || r.dateMs > lastReadingMs);
  if (!newReadings.length) return;

  const patientRef = db().collection('patients').doc(patientId);
  const batch = db().batch();
  let maxMs = lastReadingMs ?? 0;
  for (const reading of newReadings) {
    batch.set(patientRef.collection('readings').doc(), {
      ...reading,
      fetchedAt: nowMs,
      insulinSensitivityFactor: physiology.insulinSensitivityFactor,
      carbRatio: physiology.carbRatio,
    });
    maxMs = Math.max(maxMs, reading.dateMs);
  }
  await batch.commit();
  await patientRef.update({ lastReadingMs: maxMs });
}

// Snapshotted onto each new reading (see persistNewReadings) so a training
// join knows what was actually in effect at that point in time.
async function getPhysiology(patientId: string): Promise<PatientPhysiology> {
  const doc = await db().collection('patients').doc(patientId).collection('thresholds').doc('current').get();
  return { ...DEFAULT_PHYSIOLOGY, ...(doc.data() ?? {}) };
}

// Covers predictors.py's 30-minute IOB/COB trend window even with a missed cycle.
const IOB_COB_HISTORY_LIMIT = 15;

// Gluroo's devicestatus.json only ever returns a single current snapshot,
// not real history (confirmed against this account), so this builds the
// history ourselves: read the rolling window, append this cycle's point,
// trim, write back. 1 read + 1 write per cycle, only when there's an
// iob/cob value worth recording. Used solely by predict_multi_bolus.
async function updateIobCobHistory(patientId: string, point: DeviceStatusPoint): Promise<DeviceStatusPoint[]> {
  if (point.iob === null && point.cob === null) return [];
  const historyRef = db().collection('patients').doc(patientId).collection('iobCobHistory').doc('recent');
  const doc = await historyRef.get();
  const existing = (doc.data()?.points ?? []) as DeviceStatusPoint[];
  const updated = [...existing, point].sort((a, b) => a.dateMs - b.dateMs).slice(-IOB_COB_HISTORY_LIMIT);
  await historyRef.set({ points: updated });
  return updated;
}

// Cursor-based (lastTreatmentMs), not "most recent N" every cycle - keeps
// write volume proportional to how often boluses/carbs happen, not poll cadence.
async function ingestNewTreatments(
  patientId: string,
  patient: FirebaseFirestore.DocumentData,
  apiSecret: string
): Promise<void> {
  const lastTreatmentMs = typeof patient.lastTreatmentMs === 'number' ? patient.lastTreatmentMs : null;
  const newTreatments = await fetchTreatmentsSince(patient.nightscoutUrl, apiSecret, lastTreatmentMs);
  if (!newTreatments.length) return;

  const patientRef = db().collection('patients').doc(patientId);
  const batch = db().batch();
  let maxMs = lastTreatmentMs ?? 0;
  for (const treatment of newTreatments) {
    batch.set(patientRef.collection('treatments').doc(treatment.nightscoutId), treatment);
    maxMs = Math.max(maxMs, treatment.mills);
  }
  await batch.commit();
  await patientRef.update({ lastTreatmentMs: maxMs });
  console.log(`ingestNewTreatments(${patientId}): stored ${newTreatments.length} new treatment(s)`);
}

async function updatePairedDevices(
  patientId: string,
  eventsByUid: Map<string, AlertEvent[]>,
  nowMs: number
): Promise<void> {
  const devicesSnap = await db().collection('devices').where('patientId', '==', patientId).get();
  if (devicesSnap.empty) return;

  // Bucketing the id by a 10-minute window means an unresolved alert
  // re-triggers the ESP32's buzz periodically instead of every single poll.
  const bucket = Math.floor(nowMs / (10 * 60 * 1000));

  await Promise.all(
    devicesSnap.docs.map((d) => {
      // Driven by whichever member paired this specific device, so it buzzes
      // according to that person's own thresholds - not some shared/blended
      // set, and not silently empty if pairedBy isn't in eventsByUid (e.g. a
      // member removed after pairing).
      const events = eventsByUid.get(d.data().pairedBy) ?? [];
      const worst = [...events].sort((a, b) => severityRank(b.severity) - severityRank(a.severity))[0];
      const payload = worst
        ? { severity: worst.severity, alertId: `${worst.type}-${bucket}`, message: worst.message, timestamp: nowMs }
        : { severity: 'NONE', alertId: `clear-${bucket}`, message: '', timestamp: nowMs };
      return rtdb().ref(`devices/${d.id}/alert`).set(payload);
    })
  );
}

function severityRank(s: string): number {
  return s === 'CRITICAL' ? 2 : s === 'WARNING' ? 1 : 0;
}

/** Used by the Android app's Setup screen to validate a Gluroo URL + API
 * secret before the owner saves them. */
export const verifyGlurooConnection = onCall(async (request) => {
  const { nightscoutUrl, apiSecret } = (request.data ?? {}) as {
    nightscoutUrl?: string;
    apiSecret?: string;
  };
  if (!nightscoutUrl || !apiSecret) {
    throw new HttpsError('invalid-argument', 'nightscoutUrl and apiSecret are required.');
  }
  return verifyConnection(nightscoutUrl, apiSecret);
});

/** Persists a verified Gluroo URL + API secret for a patient the caller
 * owns. The secret goes to Secret Manager, never to Firestore. */
export const savePatientCredentials = onCall(async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in required.');
  const { patientId, nightscoutUrl, apiSecret } = (request.data ?? {}) as {
    patientId?: string;
    nightscoutUrl?: string;
    apiSecret?: string;
  };
  if (!patientId || !nightscoutUrl || !apiSecret) {
    throw new HttpsError('invalid-argument', 'patientId, nightscoutUrl and apiSecret are required.');
  }

  const patientDoc = await db().collection('patients').doc(patientId).get();
  if (!patientDoc.exists || patientDoc.data()?.ownerUid !== request.auth.uid) {
    throw new HttpsError('permission-denied', 'Only the patient owner can set credentials.');
  }

  await storePatientSecret(projectId, patientId, apiSecret);
  await db().collection('patients').doc(patientId).update({ nightscoutUrl });
  return { ok: true };
});

// Shared by claimIobSource and joinPatientAsFollower - adds uid as a
// follower unless it's already the owner; safe to call unconditionally.
async function ensureFollower(
  patientRef: FirebaseFirestore.DocumentReference,
  patientDoc: FirebaseFirestore.DocumentSnapshot,
  uid: string,
  displayName: string
): Promise<void> {
  if (patientDoc.data()?.ownerUid === uid) return;
  await patientRef.update({ memberUids: admin.firestore.FieldValue.arrayUnion(uid) });
  await patientRef.collection('members').doc(uid).set(
    { role: 'follower', displayName },
    { merge: true }
  );
}

// Self-service: caller becomes the trusted IOB source directly, no owner
// approval - knowing the patientId is already the trust boundary. Whoever
// claims most recently wins. Also makes the caller a follower (ensureFollower).
export const claimIobSource = onCall(async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in required.');
  const { patientId } = (request.data ?? {}) as { patientId?: string };
  if (!patientId) {
    throw new HttpsError('invalid-argument', 'patientId is required.');
  }

  const patientRef = db().collection('patients').doc(patientId);
  const patientDoc = await patientRef.get();
  if (!patientDoc.exists) {
    throw new HttpsError('not-found', 'No patient with that ID.');
  }

  const uid = request.auth.uid;
  await patientRef.update({ iobSourceUid: uid });
  await ensureFollower(
    patientRef,
    patientDoc,
    uid,
    request.auth.token.name || request.auth.token.email || 'IOB source'
  );
  // Returned so the app can show *whose* record this device just linked to -
  // patientId alone is an opaque string a caretaker can't visually verify,
  // and entering the wrong one (e.g. their own record's ID instead of the
  // person they're reporting for) previously succeeded silently with no way
  // to notice the mistake.
  return { ok: true, displayName: patientDoc.data()?.displayName ?? '' };
});

// Self-service: whoever has the patientId (shared out-of-band, e.g. the
// copy button in Settings) can add themselves as a read-only follower.
export const joinPatientAsFollower = onCall(async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in required.');
  const { patientId, displayName: followerDisplayName } = (request.data ?? {}) as {
    patientId?: string;
    displayName?: string;
  };
  if (!patientId) {
    throw new HttpsError('invalid-argument', 'patientId is required.');
  }

  const patientRef = db().collection('patients').doc(patientId);
  const patientDoc = await patientRef.get();
  if (!patientDoc.exists) {
    throw new HttpsError('not-found', 'No patient with that ID.');
  }

  const uid = request.auth.uid;
  await ensureFollower(
    patientRef,
    patientDoc,
    uid,
    followerDisplayName || request.auth.token.name || request.auth.token.email || 'Follower'
  );
  return { ok: true, displayName: patientDoc.data()?.displayName ?? '' };
});

// Owner can't leave their own patient this way - only ever removes a follower.
export const leavePatient = onCall(async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in required.');
  const { patientId } = (request.data ?? {}) as { patientId?: string };
  if (!patientId) {
    throw new HttpsError('invalid-argument', 'patientId is required.');
  }

  const patientRef = db().collection('patients').doc(patientId);
  const patientDoc = await patientRef.get();
  if (!patientDoc.exists) {
    throw new HttpsError('not-found', 'No patient with that ID.');
  }

  const uid = request.auth.uid;
  if (patientDoc.data()?.ownerUid === uid) {
    throw new HttpsError('failed-precondition', 'The owner cannot leave their own patient record.');
  }

  await patientRef.update({ memberUids: admin.firestore.FieldValue.arrayRemove(uid) });
  await patientRef.collection('members').doc(uid).delete();

  // An ex-member's phone shouldn't remain trusted to report IOB for a
  // patient they've just disconnected from.
  if (patientDoc.data()?.iobSourceUid === uid) {
    await patientRef.update({ iobSourceUid: admin.firestore.FieldValue.delete() });
  }

  return { ok: true };
});

/** Links an ESP32's self-reported deviceId to a patient the caller owns. */
export const pairMcuDevice = onCall(async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in required.');
  const { patientId, deviceId } = (request.data ?? {}) as { patientId?: string; deviceId?: string };
  if (!patientId || !deviceId) {
    throw new HttpsError('invalid-argument', 'patientId and deviceId are required.');
  }

  const patientDoc = await db().collection('patients').doc(patientId).get();
  if (!patientDoc.exists) {
    throw new HttpsError('not-found', 'No patient with that ID.');
  }
  // Any member can pair a device, not just the owner - updatePairedDevices
  // drives each device off its own pairedBy member's thresholds.
  const memberDoc = await db()
    .collection('patients')
    .doc(patientId)
    .collection('members')
    .doc(request.auth.uid)
    .get();
  if (!memberDoc.exists) {
    throw new HttpsError('permission-denied', 'Only a member of this patient can pair a device.');
  }

  const nowMs = Date.now();
  await db().collection('devices').doc(deviceId).set({
    patientId,
    pairedAt: nowMs,
    pairedBy: request.auth.uid,
  });
  await rtdb().ref(`devices/${deviceId}/alert`).set({
    severity: 'NONE',
    alertId: `paired-${nowMs}`,
    message: '',
    timestamp: nowMs,
  });
  return { ok: true };
});
