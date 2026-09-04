/**
 * One-off local script - not deployed as a Cloud Function (not exported
 * from index.ts, so `firebase deploy --only functions` never picks it up).
 *
 * Pulls a patient's readings, treatments, and externalIobHistory, aligns
 * them by timestamp into one CSV row per reading, and reports any gap in
 * the readings sequence wider than expected - see persistNewReadings in
 * index.ts for what closes those gaps going forward; this only reports on
 * data already in Firestore.
 *
 * Usage (from backend/functions/):
 *   npm run build
 *   node lib/scripts/exportTrainingData.js <patientId> [outputDir]
 *
 * Needs Application Default Credentials to reach Firestore from outside the
 * Cloud Functions runtime: either run `gcloud auth application-default
 * login` once, or set GOOGLE_APPLICATION_CREDENTIALS to a service account
 * key JSON downloaded from the Firebase console (Project settings > Service
 * accounts).
 */
import * as admin from 'firebase-admin';
import * as fs from 'fs';
import * as path from 'path';

interface ReadingDoc {
  dateMs: number;
  sgv: number;
  direction: string;
  iob: number | null;
  iobUnreliable?: boolean;
}

interface TreatmentDoc {
  mills: number;
  insulin: number | null;
  carbs: number | null;
}

interface ExternalIobDoc {
  reportedAt: number;
  iob: number;
}

// Matches GlucosePredictor.kt's INSULIN_DURATION_MINUTES - how far back to
// look for treatments still "active" relative to a given reading.
const INSULIN_WINDOW_MS = 4 * 60 * 60 * 1000;

// A poll every 5 minutes is normal; flag anything wider than 2x that as a
// gap worth looking at (see persistNewReadings's doc comment in index.ts).
const GAP_THRESHOLD_MS = 10 * 60 * 1000;

function loadProjectId(): string {
  // Compiled location is backend/functions/lib/scripts/ (see tsconfig's
  // outDir), so three levels up reaches backend/.firebaserc.
  const rcPath = path.resolve(__dirname, '../../../.firebaserc');
  const rc = JSON.parse(fs.readFileSync(rcPath, 'utf8'));
  return rc.projects.default;
}

function csvEscape(value: string | number): string {
  const s = String(value);
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function writeCsv(filePath: string, rows: Array<Record<string, string | number>>): void {
  if (!rows.length) {
    fs.writeFileSync(filePath, '');
    return;
  }
  const headers = Object.keys(rows[0]);
  const lines = [headers.join(',')];
  for (const row of rows) {
    lines.push(headers.map((h) => csvEscape(row[h])).join(','));
  }
  fs.writeFileSync(filePath, lines.join('\n') + '\n');
}

async function fetchAll(
  db: admin.firestore.Firestore,
  patientId: string
): Promise<{ readings: ReadingDoc[]; treatments: TreatmentDoc[]; externalIob: ExternalIobDoc[] }> {
  const patientRef = db.collection('patients').doc(patientId);
  const [readingsSnap, treatmentsSnap, externalIobSnap] = await Promise.all([
    patientRef.collection('readings').orderBy('dateMs', 'asc').get(),
    patientRef.collection('treatments').orderBy('mills', 'asc').get(),
    patientRef.collection('externalIobHistory').orderBy('reportedAt', 'asc').get(),
  ]);
  return {
    readings: readingsSnap.docs.map((d) => d.data() as ReadingDoc),
    treatments: treatmentsSnap.docs.map((d) => d.data() as TreatmentDoc),
    externalIob: externalIobSnap.docs.map((d) => d.data() as ExternalIobDoc),
  };
}

/** Builds one aligned row per reading:
 * - external IOB: nearest-past (backward asof) match on reportedAt <= dateMs,
 *   via a single forward-advancing pointer since both arrays are sorted -
 *   see backend/README.md's training-join note for why "nearest past", not
 *   "nearest either direction" (a training row shouldn't see the future).
 * - treatment features: raw (not decay-curve-weighted) summaries of what's
 *   active in the trailing INSULIN_WINDOW_MS - last dose/time-ago and a
 *   trailing-window total, for both insulin and carbs. Left this way
 *   (rather than pre-computing an "expected effect" like
 *   MultiBolusInsulinActivityPredictor does) so a model trained on this
 *   export learns the dose/glucose relationship itself instead of being
 *   fed the rule-based predictor's own assumption about it. */
function buildAlignedRows(
  readings: ReadingDoc[],
  treatments: TreatmentDoc[],
  externalIob: ExternalIobDoc[]
): Array<Record<string, string | number>> {
  const rows: Array<Record<string, string | number>> = [];
  let iobPointer = -1;

  for (const reading of readings) {
    while (iobPointer + 1 < externalIob.length && externalIob[iobPointer + 1].reportedAt <= reading.dateMs) {
      iobPointer++;
    }
    const nearestIob = iobPointer >= 0 ? externalIob[iobPointer] : null;

    const active = treatments.filter(
      (t) => t.mills <= reading.dateMs && reading.dateMs - t.mills < INSULIN_WINDOW_MS
    );
    const boluses = active.filter((t) => (t.insulin ?? 0) > 0);
    const carbs = active.filter((t) => (t.carbs ?? 0) > 0);
    const lastBolus = boluses[boluses.length - 1];
    const lastCarb = carbs[carbs.length - 1];

    rows.push({
      dateMs: reading.dateMs,
      timestampIso: new Date(reading.dateMs).toISOString(),
      sgv: reading.sgv,
      direction: reading.direction,
      gluroo_iob: reading.iob ?? '',
      iob_unreliable: reading.iobUnreliable ? 1 : 0,
      external_iob: nearestIob ? nearestIob.iob : '',
      external_iob_age_min: nearestIob ? Math.round((reading.dateMs - nearestIob.reportedAt) / 60000) : '',
      minutes_since_last_bolus: lastBolus ? Math.round((reading.dateMs - lastBolus.mills) / 60000) : '',
      last_bolus_units: lastBolus?.insulin ?? '',
      insulin_units_trailing_4h: round2(boluses.reduce((sum, t) => sum + (t.insulin ?? 0), 0)),
      minutes_since_last_carb: lastCarb ? Math.round((reading.dateMs - lastCarb.mills) / 60000) : '',
      last_carb_grams: lastCarb?.carbs ?? '',
      carbs_g_trailing_4h: round2(carbs.reduce((sum, t) => sum + (t.carbs ?? 0), 0)),
    });
  }
  return rows;
}

function round2(n: number): number {
  return Math.round(n * 100) / 100;
}

/** Prints (doesn't fix) any gap between consecutive readings wider than
 * GAP_THRESHOLD_MS - each one is a stretch where a real CGM value that
 * existed was never captured (see persistNewReadings's doc comment). */
function reportGaps(readings: ReadingDoc[]): void {
  let gapCount = 0;
  for (let i = 1; i < readings.length; i++) {
    const deltaMs = readings[i].dateMs - readings[i - 1].dateMs;
    if (deltaMs > GAP_THRESHOLD_MS) {
      gapCount++;
      console.warn(
        `  gap: ${Math.round(deltaMs / 60000)} min between ` +
          `${new Date(readings[i - 1].dateMs).toISOString()} and ${new Date(readings[i].dateMs).toISOString()}`
      );
    }
  }
  console.log(`${gapCount} gap(s) > ${GAP_THRESHOLD_MS / 60000} min found across ${readings.length} readings.`);
}

async function main(): Promise<void> {
  const patientId = process.argv[2];
  if (!patientId) {
    console.error('Usage: node lib/scripts/exportTrainingData.js <patientId> [outputDir]');
    process.exit(1);
  }
  const outDir = process.argv[3] ?? path.resolve(__dirname, '../../training-data-export');
  fs.mkdirSync(outDir, { recursive: true });

  admin.initializeApp({ projectId: loadProjectId() });
  const db = admin.firestore();

  console.log(`Fetching readings/treatments/externalIobHistory for patient ${patientId}...`);
  const { readings, treatments, externalIob } = await fetchAll(db, patientId);
  console.log(
    `  readings: ${readings.length}, treatments: ${treatments.length}, externalIobHistory: ${externalIob.length}`
  );

  reportGaps(readings);

  const rows = buildAlignedRows(readings, treatments, externalIob);
  const outPath = path.join(outDir, `${patientId}-aligned-${Date.now()}.csv`);
  writeCsv(outPath, rows);
  console.log(`Wrote ${rows.length} aligned row(s) to ${outPath}`);
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error(err);
    process.exit(1);
  });
