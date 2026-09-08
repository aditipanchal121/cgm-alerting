import * as admin from 'firebase-admin';
import { GlucoseReading, PatientPhysiology } from './types';
import { DeviceStatusPoint } from './nightscout';
import { fetchExperimentalPredictions } from './experimentalPredictors';

const HORIZON_MINUTES = 30;

/** Calls the Python experimental predictors once per cycle and uses that one
 * result for both livePredictions/current (Android's Predictions tab) and
 * predictionAccuracy (append-only history + running summary, offline
 * analysis only). Never allowed to affect real alerting - see the
 * try/catch around the call site in index.ts. */
export async function updateExperimentalPredictions(
  db: admin.firestore.Firestore,
  patientId: string,
  readings: GlucoseReading[],
  physiology: PatientPhysiology,
  iobCobHistory: DeviceStatusPoint[],
  nowMs: number
): Promise<void> {
  const last = readings[readings.length - 1];
  if (!last) return;

  const outputs = await fetchExperimentalPredictions(readings, physiology, HORIZON_MINUTES, iobCobHistory);
  const predictions: Record<string, number | null> = {};
  const notes: Record<string, string | null> = {};
  for (const output of outputs) {
    predictions[output.key] = output.projectedValue;
    notes[output.key] = output.note;
  }

  const patientRef = db.collection('patients').doc(patientId);
  const accuracyRef = patientRef.collection('predictionAccuracy');

  const batch = db.batch();

  batch.set(patientRef.collection('livePredictions').doc('current'), { outputs, updatedAtMs: nowMs });

  // At most one prediction is due per cycle in steady state; limit(1)
  // keeps this a single-doc read even if a gap left more than one waiting.
  const dueSnap = await accuracyRef
    .where('resolved', '==', false)
    .where('targetMs', '<=', nowMs)
    .orderBy('targetMs', 'asc')
    .limit(1)
    .get();

  if (!dueSnap.empty) {
    const due = dueSnap.docs[0];
    const duePredictions = (due.data().predictions ?? {}) as Record<string, number | null>;
    const errors: Record<string, number | null> = {};
    const summaryUpdate: Record<string, FirebaseFirestore.FieldValue> = {};
    for (const [key, predicted] of Object.entries(duePredictions)) {
      if (predicted === null) {
        errors[key] = null;
        continue;
      }
      const error = predicted - last.sgv;
      errors[key] = error;
      // MAE/mean bias only - a live sanity check. RMSE, directional
      // accuracy, and full/degraded splits are reconstructable offline from
      // the raw predictions/notes/startSgv/actualSgv/errors fields below.
      summaryUpdate[`${key}.count`] = admin.firestore.FieldValue.increment(1);
      summaryUpdate[`${key}.sumAbsError`] = admin.firestore.FieldValue.increment(Math.abs(error));
      summaryUpdate[`${key}.sumError`] = admin.firestore.FieldValue.increment(error);
    }
    batch.update(due.ref, { resolved: true, actualSgv: last.sgv, errors });
    if (Object.keys(summaryUpdate).length) {
      batch.set(accuracyRef.doc('summary'), summaryUpdate, { merge: true });
    }
  }

  // Keyed by dateMs so it's idempotent if this ever ran twice for the same
  // reading. Resolved by a future cycle once nowMs reaches targetMs.
  batch.set(accuracyRef.doc(String(last.dateMs)), {
    predictions,
    notes,
    startSgv: last.sgv,
    createdAtMs: nowMs,
    targetMs: last.dateMs + HORIZON_MINUTES * 60000,
    resolved: false,
  });

  await batch.commit();
}
