import * as admin from 'firebase-admin';

// Two poll cycles' worth of buffer - Cloud Scheduler's actual invocation
// cadence can drift a bit (deploys, cold starts), and this keeps that
// drift from falsely flagging a still-current report as stale.
export const EXTERNAL_IOB_FRESHNESS_MS = 10 * 60 * 1000;

interface ExternalIobDoc {
  iob: number;
  reportedAt: number;
  reportedBy: string;
}

export interface ExternalIobResult {
  iob: number;
  fresh: boolean;
}

/** Returns the externally-reported IOB for a patient, or null if none has
 * ever been reported (pollOnePatient falls back to Gluroo's own IOB field
 * in that case only). Once a report exists, it's preferred over Gluroo's
 * field indefinitely - `fresh` says whether it's within the freshness
 * window, so a stale report is flagged unreliable rather than discarded,
 * since Gluroo's own field is frequently absent for this account and isn't
 * a trustworthy fallback either. */
export async function getExternalIob(
  db: admin.firestore.Firestore,
  patientId: string,
  nowMs: number
): Promise<ExternalIobResult | null> {
  const doc = await db
    .collection('patients')
    .doc(patientId)
    .collection('externalIob')
    .doc('current')
    .get();
  const data = doc.data() as ExternalIobDoc | undefined;
  if (!data || typeof data.iob !== 'number' || typeof data.reportedAt !== 'number') return null;
  return { iob: data.iob, fresh: nowMs - data.reportedAt <= EXTERNAL_IOB_FRESHNESS_MS };
}
