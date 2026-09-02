import { onSchedule } from 'firebase-functions/v2/scheduler';
import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { setGlobalOptions } from 'firebase-functions/v2';
import * as admin from 'firebase-admin';
import { fetchRecentReadings, verifyConnection } from './nightscout';
import { evaluateAlerts } from './alertEngine';
import { sendAlertPush, sendReadingStatusPush } from './fcm';
import { getPatientSecret, storePatientSecret } from './secretManager';
import { deleteOldAlerts, deleteOldReadings } from './cleanup';
import { getFreshExternalIob, pushExternalIobUpdate } from './externalIob';
import { AlertEvent, Thresholds } from './types';

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
};

/** The reliability core: polls every registered patient's Gluroo data on a
 * fixed schedule regardless of whether any phone is on, evaluates alerts,
 * and fans out to Firestore history, FCM push, and any paired ESP32. */
export const pollGlucose = onSchedule('every 5 minutes', async () => {
  const patientsSnap = await db().collection('patients').get();
  await Promise.all(patientsSnap.docs.map((doc) => pollOnePatient(doc.id, doc.data())));
});

/** Alerts are only ever shown as recent history in the app, and can be
 * written every poll cycle a condition holds - without this, the alerts
 * collection grows without bound. */
export const cleanupOldAlerts = onSchedule('every 24 hours', async () => {
  const deleted = await deleteOldAlerts(db());
  console.log(`cleanupOldAlerts: deleted ${deleted} alert(s) older than the retention window`);
});

/** Readings are kept for a full year (see cleanup.ts) as a deliberate
 * training-data retention policy, not left to grow unbounded by accident. */
export const cleanupOldReadings = onSchedule('every 24 hours', async () => {
  const deleted = await deleteOldReadings(db());
  console.log(`cleanupOldReadings: deleted ${deleted} reading(s) older than the retention window`);
});

async function pollOnePatient(patientId: string, patient: FirebaseFirestore.DocumentData): Promise<void> {
  try {
    const apiSecret = await getPatientSecret(projectId, patientId);
    const readings = await fetchRecentReadings(patient.nightscoutUrl, apiSecret, 6);
    if (!readings.length) return;

    const nowMs = Date.now();
    const latest = readings[readings.length - 1];

    // Gluroo's devicestatus feed has occasionally reset IOB to exactly 0 from
    // a much higher value within a single 5-minute poll, which isn't
    // physiologically plausible - insulin doesn't disappear that fast. Flag
    // (never hide) that specific case so the dashboard can tell family/owner
    // the zero might be a Gluroo-side glitch, while still showing genuine
    // zeros (small or no prior IOB) as normal.
    //
    // The baseline is the last known *non-zero* IOB, persisted on the patient
    // doc rather than read from the previous reading - a glitch can produce
    // several consecutive zero polls in a row, and comparing each one only to
    // the poll before it would clear the flag after the first zero (0 vs 0
    // looks like "no drop"). The flag needs to stay up across all of them
    // until a real non-zero reading confirms Gluroo has recovered.
    const externalIob = await getFreshExternalIob(db(), patientId, nowMs);
    if (externalIob !== null) {
      // A directly-reported IOB (e.g. a paired phone's NotificationListenerService
      // reading a pump app's own notification) is trusted outright - it isn't
      // Gluroo's own devicestatus feed, so the glitch-detection heuristic below
      // doesn't apply to it.
      latest.iob = externalIob;
      latest.iobUnreliable = false;
    } else if (latest.iob === 0) {
      const lastNonZeroIob = typeof patient.lastNonZeroIob === 'number' ? patient.lastNonZeroIob : null;
      latest.iobUnreliable = lastNonZeroIob !== null && lastNonZeroIob > 0.5;
    } else if (latest.iob !== null) {
      await db().collection('patients').doc(patientId).update({ lastNonZeroIob: latest.iob });
    }

    await db()
      .collection('patients')
      .doc(patientId)
      .collection('readings')
      .add({ ...latest, fetchedAt: nowMs });

    // Always driven by the actual reading, independent of whether it crossed
    // any alert threshold - this keeps a persistent status notification
    // current, separate from the low/high/predictive alert notifications.
    await sendReadingStatusPush(patientId, patient.displayName ?? 'NightWatch', latest);

    const thresholdsDoc = await db()
      .collection('patients')
      .doc(patientId)
      .collection('thresholds')
      .doc('current')
      .get();
    const thresholds: Thresholds = { ...DEFAULT_THRESHOLDS, ...(thresholdsDoc.data() ?? {}) };

    const events = evaluateAlerts(readings, thresholds, nowMs);

    for (const event of events) {
      await db().collection('patients').doc(patientId).collection('alerts').add(event);
      if (event.severity !== 'INFO') {
        await sendAlertPush(patientId, patient.displayName ?? 'NightWatch', event);
      }
    }

    await updatePairedDevices(patientId, events, nowMs);
  } catch (err) {
    console.error(`pollGlucose failed for patient ${patientId}`, err);
  }
}

async function updatePairedDevices(
  patientId: string,
  events: AlertEvent[],
  nowMs: number
): Promise<void> {
  const devicesSnap = await db().collection('devices').where('patientId', '==', patientId).get();
  if (devicesSnap.empty) return;

  const worst = [...events].sort((a, b) => severityRank(b.severity) - severityRank(a.severity))[0];
  // Bucketing the id by a 10-minute window means an unresolved alert
  // re-triggers the ESP32's buzz periodically instead of every single poll.
  const bucket = Math.floor(nowMs / (10 * 60 * 1000));
  const payload = worst
    ? { severity: worst.severity, alertId: `${worst.type}-${bucket}`, message: worst.message, timestamp: nowMs }
    : { severity: 'NONE', alertId: `clear-${bucket}`, message: '', timestamp: nowMs };

  await Promise.all(devicesSnap.docs.map((d) => rtdb().ref(`devices/${d.id}/alert`).set(payload)));
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

/** Owner-only: designates which signed-in user's device is trusted to
 * report IOB directly (e.g. via a NotificationListenerService reading a
 * pump app's own notification) instead of relying solely on Gluroo's own
 * devicestatus IOB. The actual write permission is enforced by
 * firestore.rules checking this field, not by anything client-side -
 * this callable is just how it gets set in the first place. */
export const setIobSource = onCall(async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in required.');
  const { patientId, sourceUid } = (request.data ?? {}) as { patientId?: string; sourceUid?: string };
  if (!patientId || !sourceUid) {
    throw new HttpsError('invalid-argument', 'patientId and sourceUid are required.');
  }

  const patientDoc = await db().collection('patients').doc(patientId).get();
  if (!patientDoc.exists || patientDoc.data()?.ownerUid !== request.auth.uid) {
    throw new HttpsError('permission-denied', 'Only the patient owner can set the IOB source.');
  }

  await db().collection('patients').doc(patientId).update({ iobSourceUid: sourceUid });
  return { ok: true };
});

/** Fires within seconds of the designated IOB source device writing a fresh
 * value - not on the 5-minute pollGlucose schedule - so the persistent
 * notification/widget reflect it as close to immediately as possible. */
export const onExternalIobWritten = onDocumentWritten(
  'patients/{patientId}/externalIob/current',
  async (event) => {
    const afterSnap = event.data?.after;
    if (!afterSnap || !afterSnap.exists) return;
    const iob = afterSnap.data()?.iob;
    if (typeof iob !== 'number') return;
    await pushExternalIobUpdate(db(), event.params.patientId, iob);
  }
);

/** Links an ESP32's self-reported deviceId to a patient the caller owns. */
export const pairMcuDevice = onCall(async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in required.');
  const { patientId, deviceId } = (request.data ?? {}) as { patientId?: string; deviceId?: string };
  if (!patientId || !deviceId) {
    throw new HttpsError('invalid-argument', 'patientId and deviceId are required.');
  }

  const patientDoc = await db().collection('patients').doc(patientId).get();
  if (!patientDoc.exists || patientDoc.data()?.ownerUid !== request.auth.uid) {
    throw new HttpsError('permission-denied', 'Only the patient owner can pair a device.');
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
