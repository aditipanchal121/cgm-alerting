import { SecretManagerServiceClient } from '@google-cloud/secret-manager';

let _client: SecretManagerServiceClient | undefined;
function client(): SecretManagerServiceClient {
  if (!_client) _client = new SecretManagerServiceClient();
  return _client;
}

function secretIdForPatient(patientId: string): string {
  return `gluroo-api-secret-${patientId}`;
}

/** Creates (if needed) and writes a new version of a patient's Gluroo API
 * secret. Kept in Secret Manager rather than Firestore since it's a
 * health-data credential. */
export async function storePatientSecret(
  projectId: string,
  patientId: string,
  apiSecret: string
): Promise<void> {
  const secretId = secretIdForPatient(patientId);
  const parent = `projects/${projectId}`;

  try {
    await client().createSecret({
      parent,
      secretId,
      secret: { replication: { automatic: {} } },
    });
  } catch (err: any) {
    if (err?.code !== 6 /* ALREADY_EXISTS */) throw err;
  }

  await client().addSecretVersion({
    parent: `${parent}/secrets/${secretId}`,
    payload: { data: Buffer.from(apiSecret, 'utf8') },
  });
}

export async function getPatientSecret(projectId: string, patientId: string): Promise<string> {
  const secretId = secretIdForPatient(patientId);
  const [version] = await client().accessSecretVersion({
    name: `projects/${projectId}/secrets/${secretId}/versions/latest`,
  });
  const data = version.payload?.data;
  if (!data) throw new Error(`No secret data stored for patient ${patientId}`);
  return Buffer.from(data).toString('utf8');
}
