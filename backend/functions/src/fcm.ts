import * as admin from 'firebase-admin';
import { AlertEvent } from './types';

/** Pushes an alert to every member (owner + followers) of a patient. */
export async function sendAlertPush(
  patientId: string,
  displayName: string,
  event: AlertEvent
): Promise<void> {
  const db = admin.firestore();
  const membersSnap = await db.collection('patients').doc(patientId).collection('members').get();
  const uids = membersSnap.docs.map((d) => d.id);
  if (!uids.length) return;

  const tokens: string[] = [];
  for (const uid of uids) {
    const tokensSnap = await db.collection('users').doc(uid).collection('fcmTokens').get();
    tokensSnap.forEach((t) => tokens.push(t.id));
  }
  if (!tokens.length) return;

  const isCritical = event.severity === 'CRITICAL';
  const message: admin.messaging.MulticastMessage = {
    tokens,
    notification: {
      title: `${displayName}: ${event.type.replace(/_/g, ' ')}`,
      body: event.message,
    },
    android: {
      priority: 'high',
      notification: {
        channelId: isCritical ? 'critical_alerts' : 'warning_alerts',
      },
    },
    data: {
      severity: event.severity,
      type: event.type,
      patientId,
      message: event.message,
    },
  };

  // TODO(production hardening): sweep response.responses for
  // messaging/registration-token-not-registered and delete those token docs.
  await admin.messaging().sendEachForMulticast(message);
}
