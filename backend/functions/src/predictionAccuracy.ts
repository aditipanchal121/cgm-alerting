import * as admin from 'firebase-admin';
import { GlucoseReading, PatientPhysiology } from './types';
import { TreatmentEvent } from './nightscout';
import { fetchExperimentalPredictions } from './experimentalPredictors';

const HORIZON_MINUTES = 30;

/** Calls the Python experimental predictors once per cycle and uses that
 * one result for two things:
 *  - patients/{id}/livePredictions/current - overwritten every cycle, the
 *    Android Predictions tab's only data source (a plain Firestore
 *    listener, same pattern as externalIob/current).
 *  - patients/{id}/predictionAccuracy - append-only history + a running
 *    per-predictor error summary, for later offline analysis (see
 *    backend/README.md). Never read by the live app.
 * Both are driven by the same single HTTP call - no duplicated computation,
 * no duplicated Firestore reads for the inputs (readings/physiology/
 * treatments are already in memory from this cycle's own fetch). Never
 * allowed to affect real alerting or notifications - see the try/catch
 * around the call site in index.ts. */
export async function updateExperimentalPredictions(
  db: admin.firestore.Firestore,
  patientId: string,
  readings: GlucoseReading[],
  physiology: PatientPhysiology,
  treatments: TreatmentEvent[],
  nowMs: number
): Promise<void> {
  const last = readings[readings.length - 1];
  if (!last) return;

  const outputs = await fetchExperimentalPredictions(readings, physiology, treatments, HORIZON_MINUTES);
  const predictions: Record<string, number | null> = {};
  for (const output of outputs) {
    predictions[output.key] = output.projectedValue;
  }

  const patientRef = db.collection('patients').doc(patientId);
  const accuracyRef = patientRef.collection('predictionAccuracy');

  const batch = db.batch();

  batch.set(patientRef.collection('livePredictions').doc('current'), { outputs, updatedAtMs: nowMs });

  // Resolve: at most one prediction is due per cycle in steady state (they
  // resolve roughly HORIZON_MINUTES/pollInterval cycles after being made,
  // one per cycle), so limit(1) keeps this a single-document read even if a
  // gap left more than one waiting.
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
      // Dot-path updates into a per-predictor map, using FieldValue.increment
      // so this never needs to read the summary doc first - increment treats
      // a missing field as 0, so the very first resolution creates it.
      summaryUpdate[`${key}.count`] = admin.firestore.FieldValue.increment(1);
      summaryUpdate[`${key}.sumAbsError`] = admin.firestore.FieldValue.increment(Math.abs(error));
      summaryUpdate[`${key}.sumError`] = admin.firestore.FieldValue.increment(error);
    }
    batch.update(due.ref, { resolved: true, actualSgv: last.sgv, errors });
    if (Object.keys(summaryUpdate).length) {
      batch.set(accuracyRef.doc('summary'), summaryUpdate, { merge: true });
    }
  }

  // Record: this cycle's new predictions, to be resolved ~HORIZON_MINUTES
  // from now by a future cycle doing exactly the above. Keyed by dateMs so
  // it's naturally idempotent if this ever ran twice for the same reading.
  batch.set(accuracyRef.doc(String(last.dateMs)), {
    predictions,
    createdAtMs: nowMs,
    targetMs: last.dateMs + HORIZON_MINUTES * 60000,
    resolved: false,
  });

  await batch.commit();
}
