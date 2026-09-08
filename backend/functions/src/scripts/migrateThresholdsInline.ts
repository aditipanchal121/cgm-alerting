/**
 * One-off local migration - not deployed (not exported from index.ts).
 *
 * Copies each existing patients/{id}/members/{uid}/thresholds/current doc's
 * fields onto its parent members/{uid} doc as a `thresholds` map field, to
 * match the new inline schema (see firestore.rules and index.ts's
 * pollOnePatient, and PatientRepository.kt's observeThresholds/saveThresholds
 * on the Android side). Idempotent and safe to re-run - each member is
 * independent, and re-running just overwrites `thresholds` with the same
 * source data again.
 *
 * Defaults to a dry run (prints what it would do, writes nothing). Pass
 * --apply to actually write. Old subcollection docs are left in place even
 * after --apply (harmless once firestore.rules no longer grants any access
 * to that path - nothing can read or write them anymore) - delete them
 * manually later once you've confirmed the new inline field is working, if
 * you want the cleanup; not required.
 *
 * Usage (from backend/functions/):
 *   npm run build
 *   node lib/scripts/migrateThresholdsInline.js            # dry run
 *   node lib/scripts/migrateThresholdsInline.js --apply     # actually write
 *
 * Needs Application Default Credentials - see exportTrainingData.ts's doc
 * comment for how to set those up.
 */
import * as admin from 'firebase-admin';
import * as fs from 'fs';
import * as path from 'path';

function loadProjectId(): string {
  const rcPath = path.resolve(__dirname, '../../../.firebaserc');
  const rc = JSON.parse(fs.readFileSync(rcPath, 'utf8'));
  return rc.projects.default;
}

async function main() {
  const apply = process.argv.includes('--apply');
  admin.initializeApp({ projectId: loadProjectId() });
  const db = admin.firestore();

  const patientsSnap = await db.collection('patients').get();
  let migrated = 0;
  let skipped = 0;

  for (const patientDoc of patientsSnap.docs) {
    const membersSnap = await patientDoc.ref.collection('members').get();
    for (const memberDoc of membersSnap.docs) {
      const oldThresholdsDoc = await memberDoc.ref.collection('thresholds').doc('current').get();
      if (!oldThresholdsDoc.exists) {
        console.log(`skip  patients/${patientDoc.id}/members/${memberDoc.id} - no old thresholds doc`);
        skipped++;
        continue;
      }
      const data = oldThresholdsDoc.data();
      console.log(
        `${apply ? 'apply' : 'dry-run'} patients/${patientDoc.id}/members/${memberDoc.id}: ` +
          `thresholds = ${JSON.stringify(data)}`
      );
      if (apply) {
        await memberDoc.ref.set({ thresholds: data }, { merge: true });
      }
      migrated++;
    }
  }

  console.log(`\n${apply ? 'Migrated' : 'Would migrate'} ${migrated} member(s), skipped ${skipped}.`);
  if (!apply) {
    console.log('Dry run only - re-run with --apply to actually write.');
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
