import * as admin from 'firebase-admin';
import { sendReadingStatusPush } from './fcm';
import { GlucoseReading } from './types';

/** How fresh an externally-reported IOB (from a paired phone's
 * NotificationListenerService reading a pump app's own notification, e.g.
 * Omnipod 5) needs to be to be trusted over Gluroo's own devicestatus IOB,
 * which has been unreliable. Past this age, pollGlucose falls back to
 * Gluroo's own value rather than trusting a source that's gone quiet. */
export const EXTERNAL_IOB_FRESHNESS_MS = 15 * 60 * 1000;

interface ExternalIobDoc {
  iob: number;
  reportedAt: number;
  reportedBy: string;
}

/** Returns the externally-reported IOB for a patient if one exists and is
 * still fresh, else null (meaning: fall back to Gluroo's own IOB). */
export async function getFreshExternalIob(
  db: admin.firestore.Firestore,
  patientId: string,
  nowMs: number
): Promise<number | null> {
  const doc = await db
    .collection('patients')
    .doc(patientId)
    .collection('externalIob')
    .doc('current')
    .get();
  const data = doc.data() as ExternalIobDoc | undefined;
  if (!data || typeof data.iob !== 'number' || typeof data.reportedAt !== 'number') return null;
  if (nowMs - data.reportedAt > EXTERNAL_IOB_FRESHNESS_MS) return null;
  return data.iob;
}

/** Fired immediately (via a Firestore trigger, not the 5-minute pollGlucose
 * schedule) whenever a fresh externally-reported IOB is written, so the
 * persistent notification/widget update within seconds instead of waiting
 * for the next poll cycle. Recombines the new IOB with whichever sgv/
 * direction the last poll already wrote - this never re-fetches Gluroo. */
export async function pushExternalIobUpdate(
  db: admin.firestore.Firestore,
  patientId: string,
  iob: number
): Promise<void> {
  const patientDoc = await db.collection('patients').doc(patientId).get();
  const patient = patientDoc.data();
  if (!patient) {
    console.warn(`pushExternalIobUpdate(${patientId}): no such patient`);
    return;
  }

  const latestReadingSnap = await db
    .collection('patients')
    .doc(patientId)
    .collection('readings')
    .orderBy('dateMs', 'desc')
    .limit(1)
    .get();
  const latestReading = latestReadingSnap.docs[0]?.data() as GlucoseReading | undefined;
  if (!latestReading) {
    console.warn(`pushExternalIobUpdate(${patientId}): no readings yet, nothing to recombine with`);
    return;
  }

  const reading: GlucoseReading = {
    ...latestReading,
    iob,
    iobUnreliable: false,
  };

  await sendReadingStatusPush(patientId, patient.displayName ?? 'Vigil', reading);
}
