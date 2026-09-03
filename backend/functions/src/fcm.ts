import * as admin from 'firebase-admin';
import { AlertEvent, GlucoseReading } from './types';

async function getMemberTokens(patientId: string): Promise<string[]> {
  const db = admin.firestore();
  const membersSnap = await db.collection('patients').doc(patientId).collection('members').get();
  const uids = membersSnap.docs.map((d) => d.id);
  if (!uids.length) {
    console.warn(`getMemberTokens(${patientId}): no members found`);
    return [];
  }

  const tokens: string[] = [];
  for (const uid of uids) {
    const tokensSnap = await db.collection('users').doc(uid).collection('fcmTokens').get();
    tokensSnap.forEach((t) => tokens.push(t.id));
  }
  console.log(`getMemberTokens(${patientId}): ${uids.length} member(s), ${tokens.length} token(s)`);
  return tokens;
}

/** Logs sendEachForMulticast's per-token result - it never throws on a
 * rejected token (e.g. one invalidated by a reinstall), so without this a
 * push can silently fail to reach anyone while the calling function still
 * reports success. */
function logMulticastResult(label: string, response: admin.messaging.BatchResponse): void {
  console.log(`${label}: ${response.successCount} succeeded, ${response.failureCount} failed`);
  response.responses.forEach((r, i) => {
    if (!r.success) {
      console.error(`${label}: token[${i}] failed - ${r.error?.code}: ${r.error?.message}`);
    }
  });
}

/** Pushes an alert to every member (owner + followers) of a patient. */
export async function sendAlertPush(
  patientId: string,
  displayName: string,
  event: AlertEvent
): Promise<void> {
  const tokens = await getMemberTokens(patientId);
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
      kind: 'alert',
      severity: event.severity,
      type: event.type,
      patientId,
      message: event.message,
    },
  };

  // TODO(production hardening): sweep response.responses for
  // messaging/registration-token-not-registered and delete those token docs.
  const response = await admin.messaging().sendEachForMulticast(message);
  logMulticastResult(`sendAlertPush(${patientId})`, response);
}

/** Pushes the latest actual reading (not predictive alerts) to every member,
 * so the phone can keep a persistent, silently-updating status notification
 * current even when the app isn't open - separate from the low/high alert
 * notifications, and driven only by real readings each poll cycle. */
export async function sendReadingStatusPush(
  patientId: string,
  displayName: string,
  reading: GlucoseReading
): Promise<void> {
  const tokens = await getMemberTokens(patientId);
  if (!tokens.length) {
    console.warn(`sendReadingStatusPush(${patientId}): no tokens, nothing sent`);
    return;
  }

  const message: admin.messaging.MulticastMessage = {
    tokens,
    // Data-only (no `notification` block) - FCM must not auto-post a new
    // notification every 5 minutes. The client updates one ongoing
    // notification in place instead.
    data: {
      kind: 'reading',
      patientId,
      displayName,
      sgv: String(reading.sgv),
      direction: reading.direction,
      dateMs: String(reading.dateMs),
      iob: reading.iob !== null ? String(reading.iob) : '',
      iobUnreliable: String(reading.iobUnreliable ?? false),
    },
    android: {
      priority: 'high',
    },
  };

  const response = await admin.messaging().sendEachForMulticast(message);
  logMulticastResult(`sendReadingStatusPush(${patientId})`, response);
}
