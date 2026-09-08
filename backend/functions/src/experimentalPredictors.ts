import { defineSecret } from 'firebase-functions/params';
import { GlucoseReading, PatientPhysiology } from './types';
import { DeviceStatusPoint } from './nightscout';

export interface ExperimentalPredictorOutput {
  key: string;
  name: string;
  description: string;
  sourceUrl: string | null;
  projectedValue: number | null;
  note: string | null;
}

const PREDICT_FUNCTION_URL = 'https://us-central1-aadi-info.cloudfunctions.net/predict_experimental';

// Shared with functions-predict/main.py's own SecretParam of the same name.
export const PREDICT_FUNCTION_SECRET = defineSecret('PREDICT_FUNCTION_SECRET');

// Callers must declare `secrets: [PREDICT_FUNCTION_SECRET]` for .value() to
// resolve. iobCobHistory is a wider devicestatus window than `readings`
// carries - see predict_multi_bolus in predictors.py.
export async function fetchExperimentalPredictions(
  readings: GlucoseReading[],
  physiology: PatientPhysiology,
  horizonMinutes: number,
  iobCobHistory: DeviceStatusPoint[]
): Promise<ExperimentalPredictorOutput[]> {
  const response = await fetch(PREDICT_FUNCTION_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Predict-Secret': PREDICT_FUNCTION_SECRET.value(),
    },
    body: JSON.stringify({ readings, physiology, horizonMinutes, iobCobHistory }),
  });
  if (!response.ok) {
    throw new Error(`predict_experimental returned ${response.status}: ${await response.text()}`);
  }
  const data = (await response.json()) as { outputs: ExperimentalPredictorOutput[] };
  return data.outputs;
}
