import * as admin from 'firebase-admin';

/** How long alert history is kept before being deleted. The History tab only
 * ever shows the most recent alerts anyway, and alertEngine can write a new
 * alert document on every single 5-minute poll cycle a condition holds (e.g.
 * IOB_HIGH re-fires each cycle IOB stays elevated, not just once on the
 * transition) - so this collection has no natural cap otherwise. */
export const ALERT_RETENTION_MS = 3 * 24 * 60 * 60 * 1000;

/** Deletes alert documents older than the retention window, across every
 * patient and every member's personal alert history. A collection-group
 * query matches "alerts" subcollections at any nesting depth (patients/{id}
 * /members/{uid}/alerts), so this doesn't need to enumerate patients or
 * members itself. Returns the number of documents deleted (for logging). */
export async function deleteOldAlerts(db: admin.firestore.Firestore): Promise<number> {
  const cutoffMs = Date.now() - ALERT_RETENTION_MS;
  const oldAlertsSnap = await db.collectionGroup('alerts').where('timestamp', '<', cutoffMs).get();

  const bulkWriter = db.bulkWriter();
  oldAlertsSnap.docs.forEach((doc) => bulkWriter.delete(doc.ref));
  await bulkWriter.close();
  return oldAlertsSnap.size;
}

/** How long glucose readings are kept before being deleted. Deliberately much
 * longer than alert history - readings are the raw dataset (sgv, direction,
 * iob, cob) intended for future model training/experimentation, not just an
 * operational log, so this is a real retention policy rather than an
 * accidental "never delete anything." At ~288 readings/day this is roughly
 * 105,000 small documents/year/patient (~15-16 MB) - storage isn't the
 * constraint here, this is just about having an explicit, bounded policy. */
export const READING_RETENTION_MS = 365 * 24 * 60 * 60 * 1000;

/** Deletes reading documents older than the retention window, across every
 * patient. Returns the number of documents deleted (for logging). */
export async function deleteOldReadings(db: admin.firestore.Firestore): Promise<number> {
  const cutoffMs = Date.now() - READING_RETENTION_MS;
  const patientsSnap = await db.collection('patients').get();

  const bulkWriter = db.bulkWriter();
  let deletedCount = 0;

  for (const patientDoc of patientsSnap.docs) {
    const oldReadingsSnap = await db
      .collection('patients')
      .doc(patientDoc.id)
      .collection('readings')
      .where('dateMs', '<', cutoffMs)
      .get();

    oldReadingsSnap.docs.forEach((doc) => {
      bulkWriter.delete(doc.ref);
      deletedCount++;
    });
  }

  await bulkWriter.close();
  return deletedCount;
}

/** Same retention reasoning as readings - this is offline-analysis/training
 * data (see predictionAccuracy.ts), not an operational log. */
export const PREDICTION_ACCURACY_RETENTION_MS = 365 * 24 * 60 * 60 * 1000;

/** Deletes prediction-accuracy documents older than the retention window,
 * across every patient. The "summary" doc has no createdAtMs field, so this
 * query never matches (and never deletes) it. */
export async function deleteOldPredictionAccuracy(db: admin.firestore.Firestore): Promise<number> {
  const cutoffMs = Date.now() - PREDICTION_ACCURACY_RETENTION_MS;
  const patientsSnap = await db.collection('patients').get();

  const bulkWriter = db.bulkWriter();
  let deletedCount = 0;

  for (const patientDoc of patientsSnap.docs) {
    const oldSnap = await patientDoc.ref.collection('predictionAccuracy').where('createdAtMs', '<', cutoffMs).get();

    oldSnap.docs.forEach((doc) => {
      bulkWriter.delete(doc.ref);
      deletedCount++;
    });
  }

  await bulkWriter.close();
  return deletedCount;
}
