import { onSchedule } from 'firebase-functions/v2/scheduler';
import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { setGlobalOptions } from 'firebase-functions/v2';
import * as admin from 'firebase-admin';
import { fetchRecentReadings, verifyConnection } from './nightscout';
import { evaluateAlerts } from './alertEngine';
import { sendAlertPush } from './fcm';
import { getPatientSecret, storePatientSecret } from './secretManager';
import { AlertEvent, Thresholds } from './types';

admin.initializeApp();
setGlobalOptions({ region: 'us-central1', maxInstances: 10 });

const db = admin.firestore();
const rtdb = admin.database();
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
  const patientsSnap = await db.collection('patients').get();
  await Promise.all(patientsSnap.docs.map((doc) => pollOnePatient(doc.id, doc.data())));
});

async function pollOnePatient(patientId: string, patient: FirebaseFirestore.DocumentData): Promise<void> {
  try {
    const apiSecret = await getPatientSecret(projectId, patientId);
    const readings = await fetchRecentReadings(patient.nightscoutUrl, apiSecret, 6);
    if (!readings.length) return;

    const nowMs = Date.now();
    const latest = readings[readings.length - 1];

    await db
      .collection('patients')
      .doc(patientId)
      .collection('readings')
      .add({ ...latest, fetchedAt: nowMs });

    const thresholdsDoc = await db
      .collection('patients')
      .doc(patientId)
      .collection('thresholds')
      .doc('current')
      .get();
    const thresholds: Thresholds = { ...DEFAULT_THRESHOLDS, ...(thresholdsDoc.data() ?? {}) };

    const events = evaluateAlerts(readings, thresholds, nowMs);

    for (const event of events) {
      await db.collection('patients').doc(patientId).collection('alerts').add(event);
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
  const devicesSnap = await db.collection('devices').where('patientId', '==', patientId).get();
  if (devicesSnap.empty) return;

  const worst = [...events].sort((a, b) => severityRank(b.severity) - severityRank(a.severity))[0];
  // Bucketing the id by a 10-minute window means an unresolved alert
  // re-triggers the ESP32's buzz periodically instead of every single poll.
  const bucket = Math.floor(nowMs / (10 * 60 * 1000));
  const payload = worst
    ? { severity: worst.severity, alertId: `${worst.type}-${bucket}`, message: worst.message, timestamp: nowMs }
    : { severity: 'NONE', alertId: `clear-${bucket}`, message: '', timestamp: nowMs };

  await Promise.all(devicesSnap.docs.map((d) => rtdb.ref(`devices/${d.id}/alert`).set(payload)));
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

  const patientDoc = await db.collection('patients').doc(patientId).get();
  if (!patientDoc.exists || patientDoc.data()?.ownerUid !== request.auth.uid) {
    throw new HttpsError('permission-denied', 'Only the patient owner can set credentials.');
  }

  await storePatientSecret(projectId, patientId, apiSecret);
  await db.collection('patients').doc(patientId).update({ nightscoutUrl });
  return { ok: true };
});

/** Links an ESP32's self-reported deviceId to a patient the caller owns. */
export const pairMcuDevice = onCall(async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in required.');
  const { patientId, deviceId } = (request.data ?? {}) as { patientId?: string; deviceId?: string };
  if (!patientId || !deviceId) {
    throw new HttpsError('invalid-argument', 'patientId and deviceId are required.');
  }

  const patientDoc = await db.collection('patients').doc(patientId).get();
  if (!patientDoc.exists || patientDoc.data()?.ownerUid !== request.auth.uid) {
    throw new HttpsError('permission-denied', 'Only the patient owner can pair a device.');
  }

  const nowMs = Date.now();
  await db.collection('devices').doc(deviceId).set({
    patientId,
    pairedAt: nowMs,
    pairedBy: request.auth.uid,
  });
  await rtdb.ref(`devices/${deviceId}/alert`).set({
    severity: 'NONE',
    alertId: `paired-${nowMs}`,
    message: '',
    timestamp: nowMs,
  });
  return { ok: true };
});
