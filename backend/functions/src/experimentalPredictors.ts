import { defineSecret } from 'firebase-functions/params';
import { GlucoseReading, PatientPhysiology } from './types';
import { TreatmentEvent } from './nightscout';

export interface ExperimentalPredictorOutput {
  key: string;
  name: string;
  description: string;
  sourceUrl: string | null;
  projectedValue: number | null;
  note: string | null;
}

// Cloud Functions v2 HTTP functions get this URL shape regardless of which
// codebase they're deployed from - codebases are a local deploy-grouping
// concept, not part of the deployed resource name.
const PREDICT_FUNCTION_URL = 'https://us-central1-aadi-info.cloudfunctions.net/predict_experimental';

/** Shared with functions-predict/main.py's own SecretParam of the same
 * name - see that file's doc comment for why this (rather than IAM invoker
 * restriction) is what locks the Python function down to just this caller. */
export const PREDICT_FUNCTION_SECRET = defineSecret('PREDICT_FUNCTION_SECRET');

/** Calls the Python experimental-predictors function - functions-predict/
 * predictors.py is the single source of truth for this math (no TS or
 * Kotlin copy exists). Callers must declare `secrets: [PREDICT_FUNCTION_SECRET]`
 * in their own onSchedule/onRequest options for `.value()` to resolve. */
export async function fetchExperimentalPredictions(
  readings: GlucoseReading[],
  physiology: PatientPhysiology,
  treatments: TreatmentEvent[],
  horizonMinutes: number
): Promise<ExperimentalPredictorOutput[]> {
  const response = await fetch(PREDICT_FUNCTION_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Predict-Secret': PREDICT_FUNCTION_SECRET.value(),
    },
    body: JSON.stringify({ readings, physiology, treatments, horizonMinutes }),
  });
  if (!response.ok) {
    throw new Error(`predict_experimental returned ${response.status}: ${await response.text()}`);
  }
  const data = (await response.json()) as { outputs: ExperimentalPredictorOutput[] };
  return data.outputs;
}
