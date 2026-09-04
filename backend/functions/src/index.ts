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

/** Temporarily off while testing the externalIob (notification-reader)
 * override path - with this on, a patient whose external reporting isn't
 * yet confirmed working still runs Gluroo's own glitch heuristic every
 * cycle, and an "iobUnreliable" flag from THAT can be confused for a
 * problem with the new reporting path being tested. The heuristic itself
 * (see pollOnePatient) is unchanged and still fully wired up - flip this
 * back to true once external reporting is confirmed reliable. */
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
    console.log(
      `pollOnePatient(${patientId}): latest reading dateMs=${latest.dateMs} ` +
        `(${new Date(latest.dateMs).toISOString()}, ${Math.round((nowMs - latest.dateMs) / 60000)} min old) sgv=${latest.sgv}`
    );

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
    } else if (GLUROO_IOB_GLITCH_DETECTION_ENABLED && latest.iob === 0) {
      const lastNonZeroIob = typeof patient.lastNonZeroIob === 'number' ? patient.lastNonZeroIob : null;
      latest.iobUnreliable = lastNonZeroIob !== null && lastNonZeroIob > 0.5;
    } else if (GLUROO_IOB_GLITCH_DETECTION_ENABLED && latest.iob !== null) {
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
    await sendReadingStatusPush(patientId, patient.displayName ?? 'Vigil', latest);

    // Alerts are evaluated per member, not once for the whole patient - each
    // family member sets their own alarm/alert thresholds (see
    // patients/{id}/members/{uid}/thresholds), so the same reading can cross
    // one person's limits and not another's. eventsByUid feeds
    // updatePairedDevices below, so each paired ESP32 alarm buzzes according
    // to whichever member paired it, not some shared/blended threshold.
    const membersSnap = await db().collection('patients').doc(patientId).collection('members').get();
    const eventsByUid = new Map<string, AlertEvent[]>();

    for (const memberDoc of membersSnap.docs) {
      const uid = memberDoc.id;
      const thresholdsDoc = await db()
        .collection('patients')
        .doc(patientId)
        .collection('members')
        .doc(uid)
        .collection('thresholds')
        .doc('current')
        .get();
      const thresholds: Thresholds = { ...DEFAULT_THRESHOLDS, ...(thresholdsDoc.data() ?? {}) };

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
          await sendAlertPush(uid, patientId, patient.displayName ?? 'Vigil', event);
        }
      }
    }

    await updatePairedDevices(patientId, eventsByUid, nowMs);
  } catch (err) {
    console.error(`pollGlucose failed for patient ${patientId}`, err);
  }
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

/** Shared by claimIobSource and joinPatientAsFollower: adds uid as a
 * follower unless it's already the owner - a no-op either way if uid is
 * already a member, so both callables can call this unconditionally
 * instead of only sometimes granting the account calling them any way to
 * view the patient it just attached to. */
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

/** Self-service: the caller becomes the trusted IOB source for this patient
 * directly - deliberately no owner-approval step. Knowing the patientId is
 * already this app's de facto shared-secret boundary (the same trust model
 * ESP32 device pairing uses), so requiring a *second* handshake here - the
 * owner manually copying the reporting device's UID - was unnecessary
 * friction for a single-family prototype. Whoever most recently claims it
 * wins; re-claiming (e.g. after switching which phone reports) is just
 * calling this again.
 *
 * Also makes the caller a follower (see ensureFollower) - claiming IOB
 * source used to be entirely independent of the members/roles system,
 * which meant reporting a patient's IOB didn't guarantee the reporting
 * account could see that patient's Dashboard at all, let alone the
 * *correct* one if it had been misconfigured against the wrong patient. */
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

/** Self-service: lets a signed-in user become a read-only follower on a
 * patient directly, given only the patientId - the app never had a UI to
 * send/accept an invite in the first place, so this replaces inviteFollower
 * (which nothing ever called) with the same shared-secret trust model as
 * claimIobSource: whoever has the patientId (shared out-of-band, e.g. via
 * the copy button in Settings) can add themselves. */
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
  // Same reasoning as claimIobSource's return value - confirms which
  // patient record was just joined rather than a bare "ok".
  return { ok: true, displayName: patientDoc.data()?.displayName ?? '' };
});

/** Self-service counterpart to joinPatientAsFollower - lets a follower undo
 * a mistaken connection (e.g. the wrong Profile ID) without needing manual
 * intervention. The owner can't leave their own patient this way - there'd
 * be no owner left - so this only ever removes a follower. */
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
  if (!patientDoc.exists) {
    throw new HttpsError('not-found', 'No patient with that ID.');
  }
  // Any member can pair a device, not just the owner - pairing an ESP32 is a
  // personal action (it's whoever's physical alarm clock this is), same
  // reasoning as alert thresholds being personal rather than owner-only:
  // updatePairedDevices drives each device off its own pairedBy member's
  // thresholds, so a follower's alarm in their own room should reflect their
  // own settings, not require the owner to have set it up for them.
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
