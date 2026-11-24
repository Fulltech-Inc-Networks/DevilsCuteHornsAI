import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { webcrypto, createHash } from "node:crypto";

const crypto = webcrypto;

function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}

class PairingMockD1 {
  constructor(token) {
    this.devices = [];
    this.sessions = new Map();
    this.pairs = new Map();
    this.addDevice({
      token,
      id: "11111111-1111-4111-8111-111111111111",
      name: "Primary Phone",
      signingPublicKey: "test-signing-key",
      encryptionPublicKey: "test-encryption-key"
    });
  }

  addDevice({
    token,
    id,
    name,
    signingPublicKey,
    encryptionPublicKey
  }) {
    this.devices.push({
      id,
      name,
      tokenHash: sha256Hex(token),
      signing_public_key: signingPublicKey,
      encryption_public_key: encryptionPublicKey,
      public_key: encryptionPublicKey,
      revoked_at: null
    });
  }

  async batch(statements) {
    const sessionsBefore = structuredClone(this.sessions);
    const pairsBefore = structuredClone(this.pairs);

    try {
      const results = [];
      for (const statement of statements) {
        results.push(await statement.run());
      }
      return results;
    } catch (error) {
      this.sessions = sessionsBefore;
      this.pairs = pairsBefore;
      throw error;
    }
  }

  prepare(sql) {
    const statement = sql.replace(/\s+/g, " ").trim();

    return {
      bind: (...params) => ({
        first: async () => {
          if (
            statement.includes("FROM devices") &&
            statement.includes("token_hash")
          ) {
            return this.devices.find(
              device => device.tokenHash === params[0] &&
                device.revoked_at === null
            ) || null;
          }

          if (
            statement.includes("FROM pairing_sessions ps") &&
            statement.includes("LEFT JOIN devices")
          ) {
            const session = this.sessions.get(params[0]);
            if (!session) return null;

            const initiator = this.devices.find(
              device => device.id === session.initiator_device_id
            );
            const recipient = this.devices.find(
              device => device.id === session.recipient_device_id
            );

            return {
              id: session.id,
              initiator_device_id: session.initiator_device_id,
              initiator_ephemeral_public_key:
                session.initiator_ephemeral_public_key,
              expires_at: session.expires_at,
              claimed_at: session.claimed_at ?? null,
              confirmation_expires_at:
                session.confirmation_expires_at ?? null,
              confirmed_at: session.confirmed_at ?? null,
              recipient_device_id: session.recipient_device_id,
              recipient_ephemeral_public_key:
                session.recipient_ephemeral_public_key,
              recipient_device_name: recipient?.name ?? null,
              recipient_revoked_at: recipient?.revoked_at ?? null,
              initiator_revoked_at: initiator?.revoked_at ?? null
            };
          }

          if (
            statement.includes("FROM pairing_sessions ps") &&
            statement.includes("JOIN devices d")
          ) {
            const session = this.sessions.get(params[0]);
            if (!session) return null;

            const initiator = this.devices.find(
              device => device.id === session.initiator_device_id
            );

            if (!initiator) return null;

            return {
              id: session.id,
              invitation_hash: session.invitation_hash,
              initiator_device_id: session.initiator_device_id,
              initiator_ephemeral_public_key:
                session.initiator_ephemeral_public_key,
              expires_at: session.expires_at,
              consumed_at: session.consumed_at ?? null,
              claimed_at: session.claimed_at ?? null,
              initiator_device_name: initiator.name,
              initiator_revoked_at: initiator.revoked_at
            };
          }

          throw new Error(`Unexpected first(): ${statement}`);
        },

        run: async () => {
          if (statement.startsWith("UPDATE devices SET last_seen_at")) {
            return { meta: { changes: 1 } };
          }

          if (statement.startsWith("INSERT INTO pairing_sessions")) {
            const [
              id,
              invitationHash,
              initiatorDeviceId,
              initiatorPublicKey,
              createdAt,
              expiresAt
            ] = params;

            this.sessions.set(id, {
              id,
              invitation_hash: invitationHash,
              initiator_device_id: initiatorDeviceId,
              initiator_ephemeral_public_key: initiatorPublicKey,
              created_at: createdAt,
              expires_at: expiresAt,
              consumed_at: null,
              recipient_device_id: null,
              claimed_at: null,
              confirmation_expires_at: null,
              recipient_ephemeral_public_key: null
            });

            return { meta: { changes: 1 } };
          }

          if (statement.includes("SET confirmed_at = ?")) {
            const [
              confirmedAt, sessionId, initiatorId, recipientId,
              claimedAt, expiryCheck, activeInitiatorId, activeRecipientId
            ] = params;

            const session = this.sessions.get(sessionId);
            const initiator = this.devices.find(
              device => device.id === activeInitiatorId
            );
            const recipient = this.devices.find(
              device => device.id === activeRecipientId
            );

            if (
              !session ||
              session.initiator_device_id !== initiatorId ||
              session.recipient_device_id !== recipientId ||
              session.claimed_at !== claimedAt ||
              session.confirmation_expires_at <= expiryCheck ||
              session.confirmed_at != null ||
              !initiator || initiator.revoked_at !== null ||
              !recipient || recipient.revoked_at !== null
            ) {
              return { meta: { changes: 0 } };
            }

            session.confirmed_at = confirmedAt;
            return { meta: { changes: 1 } };
          }

          if (statement.startsWith("INSERT INTO device_pairs")) {
            const [
              pairId, deviceA, deviceB, createdAt,
              sessionId, initiatorId, recipientId, confirmedAt
            ] = params;

            const session = this.sessions.get(sessionId);

            if (
              !session ||
              session.initiator_device_id !== initiatorId ||
              session.recipient_device_id !== recipientId ||
              session.confirmed_at !== confirmedAt
            ) {
              return { meta: { changes: 0 } };
            }

            const key = `${deviceA}:${deviceB}`;
            const existing = this.pairs.get(key);

            this.pairs.set(key, {
              id: existing?.id ?? pairId,
              device_a_id: deviceA,
              device_b_id: deviceB,
              created_at: existing?.created_at ?? createdAt,
              revoked_at: null
            });

            return { meta: { changes: 1 } };
          }

          if (statement.startsWith("UPDATE pairing_sessions")) {
            const [
              consumedAt,
              recipientDeviceId,
              claimedAt,
              confirmationExpiresAt,
              recipientPublicKey,
              sessionId,
              invitationHash,
              expiresAt,
              now
            ] = params;

            const session = this.sessions.get(sessionId);
            const initiator = session && this.devices.find(
              device => device.id === session.initiator_device_id
            );

            if (
              !session ||
              !initiator ||
              initiator.revoked_at !== null ||
              session.invitation_hash !== invitationHash ||
              session.expires_at !== expiresAt ||
              session.expires_at <= now ||
              session.consumed_at !== null ||
              session.claimed_at !== null ||
              session.recipient_device_id !== null
            ) {
              return { meta: { changes: 0 } };
            }

            session.consumed_at = consumedAt;
            session.recipient_device_id = recipientDeviceId;
            session.claimed_at = claimedAt;
            session.confirmation_expires_at = confirmationExpiresAt;
            session.recipient_ephemeral_public_key = recipientPublicKey;

            return { meta: { changes: 1 } };
          }

          throw new Error(`Unexpected run(): ${statement}`);
        }
      })
    };
  }
}

const token = "A".repeat(43);

function makeEnvironment() {
  return { DB: new PairingMockD1(token) };
}

async function makeInvitationKeys() {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDH", namedCurve: "P-256" },
    true,
    ["deriveBits"]
  );

  const bytes = new Uint8Array(
    await crypto.subtle.exportKey("raw", pair.publicKey)
  );

  return Buffer.from(bytes).toString("base64");
}

function invitationRequest(body, authorization = `Bearer ${token}`) {
  return new Request("https://device-sync.test/v1/pairings", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: authorization
    },
    body: JSON.stringify(body)
  });
}

test("creates an invitation and stores only the invitation secret hash", async () => {
  const env = makeEnvironment();
  const secretBytes = crypto.getRandomValues(new Uint8Array(32));
  const invitationSecret = Buffer.from(secretBytes).toString("base64");
  const initiatorEphemeralPublicKey = await makeInvitationKeys();

  const response = await worker.fetch(
    invitationRequest({
      invitationSecret,
      initiatorEphemeralPublicKey
    }),
    env
  );

  assert.equal(response.status, 201);

  const data = await response.json();
  assert.equal(data.initiatorDeviceName, "Primary Phone");
  assert.equal(data.protocolVersion, 1);
  assert.equal(typeof data.expiresAt, "number");
  assert.ok(data.expiresAt > Date.now());

  const stored = env.DB.sessions.get(data.sessionId);
  assert.ok(stored);
  assert.equal(stored.expires_at, data.expiresAt);
  assert.ok(stored.created_at <= data.expiresAt);
  assert.equal(stored.invitation_hash, sha256Hex(secretBytes));
  assert.notEqual(stored.invitation_hash, invitationSecret);
  assert.equal("invitationSecret" in stored, false);
  assert.equal(stored.initiator_ephemeral_public_key, initiatorEphemeralPublicKey);
});

test("rejects an invitation secret that is not 32 bytes", async () => {
  const env = makeEnvironment();

  const response = await worker.fetch(
    invitationRequest({
      invitationSecret: Buffer.from("too short").toString("base64"),
      initiatorEphemeralPublicKey: await makeInvitationKeys()
    }),
    env
  );

  assert.equal(response.status, 400);
  assert.equal(env.DB.sessions.size, 0);
});

test("rejects an invalid P-256 public key", async () => {
  const env = makeEnvironment();

  const invalidPoint = new Uint8Array(65);
  invalidPoint[0] = 4;

  const response = await worker.fetch(
    invitationRequest({
      invitationSecret: Buffer.from(
        crypto.getRandomValues(new Uint8Array(32))
      ).toString("base64"),
      initiatorEphemeralPublicKey: Buffer.from(invalidPoint).toString("base64")
    }),
    env
  );

  assert.equal(response.status, 400);
  assert.equal(env.DB.sessions.size, 0);
});

test("requires device authentication to create an invitation", async () => {
  const env = makeEnvironment();

  const response = await worker.fetch(
    invitationRequest({
      invitationSecret: Buffer.from(
        crypto.getRandomValues(new Uint8Array(32))
      ).toString("base64"),
      initiatorEphemeralPublicKey: await makeInvitationKeys()
    }, ""),
    env
  );

  assert.equal(response.status, 401);
  assert.equal(env.DB.sessions.size, 0);
});

const recipientToken = "B".repeat(43);
const recipientId = "22222222-2222-4222-8222-222222222222";

function rawEcdsaToDer(raw) {
  function integer(bytes) {
    let value = Buffer.from(bytes);
    while (value.length > 1 && value[0] === 0) {
      value = value.subarray(1);
    }
    if (value[0] & 0x80) {
      value = Buffer.concat([Buffer.from([0]), value]);
    }
    return Buffer.concat([
      Buffer.from([0x02, value.length]),
      value
    ]);
  }

  const r = integer(raw.subarray(0, 32));
  const s = integer(raw.subarray(32, 64));
  const content = Buffer.concat([r, s]);

  return Buffer.concat([
    Buffer.from([0x30, content.length]),
    content
  ]);
}

function claimMessage({
  sessionId,
  invitationSecret,
  initiatorEphemeralPublicKey,
  recipientEphemeralPublicKey,
  recipientDeviceId,
  expiresAt
}) {
  return [
    "DCH-PAIRING-CLAIM-V1",
    sessionId,
    invitationSecret,
    initiatorEphemeralPublicKey,
    recipientEphemeralPublicKey,
    recipientDeviceId,
    String(expiresAt),
    "1"
  ].join("\n");
}

async function makeSigningKeys() {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"]
  );

  const spki = await crypto.subtle.exportKey("spki", pair.publicKey);

  return {
    privateKey: pair.privateKey,
    publicKey: Buffer.from(spki).toString("base64")
  };
}

async function signClaim(privateKey, fields) {
  const raw = new Uint8Array(
    await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      privateKey,
      new TextEncoder().encode(claimMessage(fields))
    )
  );

  return rawEcdsaToDer(raw).toString("base64");
}

async function setupClaim() {
  const env = makeEnvironment();
  const signing = await makeSigningKeys();
  const initiatorSigning = await makeSigningKeys();
  env.DB.devices[0].signing_public_key = initiatorSigning.publicKey;

  env.DB.addDevice({
    token: recipientToken,
    id: recipientId,
    name: "Recipient Phone",
    signingPublicKey: signing.publicKey,
    encryptionPublicKey: "recipient-encryption-key"
  });

  const secretBytes = crypto.getRandomValues(new Uint8Array(32));
  const invitationSecret = Buffer.from(secretBytes).toString("base64");
  const initiatorEphemeralPublicKey = await makeInvitationKeys();

  const invitationResponse = await worker.fetch(
    invitationRequest({
      invitationSecret,
      initiatorEphemeralPublicKey
    }),
    env
  );

  assert.equal(invitationResponse.status, 201);
  const invitation = await invitationResponse.json();

  const recipientEphemeralPublicKey = await makeInvitationKeys();

  const fields = {
    sessionId: invitation.sessionId,
    invitationSecret,
    initiatorEphemeralPublicKey,
    recipientEphemeralPublicKey,
    recipientDeviceId: recipientId,
    expiresAt: invitation.expiresAt
  };

  const signature = await signClaim(signing.privateKey, fields);

  const request = new Request(
    `https://device-sync.test/v1/pairings/${invitation.sessionId}/claim`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${recipientToken}`
      },
      body: JSON.stringify({
        invitationSecret,
        recipientEphemeralPublicKey,
        signature
      })
    }
  );

  return {
    env,
    invitation,
    fields,
    request,
    initiatorSigning
  };
}

test("recipient claims an invitation with its registered signing key", async () => {
  const setup = await setupClaim();

  const response = await worker.fetch(setup.request, setup.env);
  const data = await response.json();

  assert.equal(response.status, 200);
  assert.equal(data.state, "claimed");
  assert.equal(data.recipientDeviceId, recipientId);
  assert.equal(data.initiatorDeviceName, "Primary Phone");
  assert.equal(data.protocolVersion, 1);
  assert.ok(data.confirmationExpiresAt > data.claimedAt);

  const stored = setup.env.DB.sessions.get(setup.invitation.sessionId);
  assert.equal(stored.recipient_device_id, recipientId);
  assert.equal(stored.consumed_at, data.claimedAt);
  assert.equal(stored.claimed_at, data.claimedAt);
  assert.equal(
    stored.recipient_ephemeral_public_key,
    setup.fields.recipientEphemeralPublicKey
  );

  // A claim does not activate a device pair.
  assert.equal(stored.confirmed_at, undefined);
});

test("rejects a pairing claim with an invalid signature", async () => {
  const setup = await setupClaim();
  const body = await setup.request.clone().json();
  body.signature = Buffer.from("invalid signature").toString("base64");

  const request = new Request(setup.request.url, {
    method: "POST",
    headers: setup.request.headers,
    body: JSON.stringify(body)
  });

  const response = await worker.fetch(request, setup.env);

  assert.equal(response.status, 401);
  assert.equal(
    setup.env.DB.sessions.get(setup.invitation.sessionId).claimed_at,
    null
  );
});

test("prevents a pairing invitation from being claimed twice", async () => {
  const setup = await setupClaim();

  const first = await worker.fetch(setup.request.clone(), setup.env);
  assert.equal(first.status, 200);

  const second = await worker.fetch(setup.request.clone(), setup.env);
  assert.equal(second.status, 409);
});

test("rejects an expired pairing invitation", async () => {
  const setup = await setupClaim();
  const session = setup.env.DB.sessions.get(setup.invitation.sessionId);
  session.expires_at = Date.now() - 1000;

  const response = await worker.fetch(setup.request, setup.env);

  assert.equal(response.status, 409);
  assert.equal(session.claimed_at, null);
});

test("prevents a device from claiming its own invitation", async () => {
  const env = makeEnvironment();
  const signing = await makeSigningKeys();

  env.DB.devices[0].signing_public_key = signing.publicKey;

  const secretBytes = crypto.getRandomValues(new Uint8Array(32));
  const invitationSecret = Buffer.from(secretBytes).toString("base64");
  const initiatorEphemeralPublicKey = await makeInvitationKeys();

  const invitationResponse = await worker.fetch(
    invitationRequest({
      invitationSecret,
      initiatorEphemeralPublicKey
    }),
    env
  );

  const invitation = await invitationResponse.json();
  const recipientEphemeralPublicKey = await makeInvitationKeys();

  const fields = {
    sessionId: invitation.sessionId,
    invitationSecret,
    initiatorEphemeralPublicKey,
    recipientEphemeralPublicKey,
    recipientDeviceId: env.DB.devices[0].id,
    expiresAt: invitation.expiresAt
  };

  const signature = await signClaim(signing.privateKey, fields);

  const request = new Request(
    `https://device-sync.test/v1/pairings/${invitation.sessionId}/claim`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`
      },
      body: JSON.stringify({
        invitationSecret,
        recipientEphemeralPublicKey,
        signature
      })
    }
  );

  const response = await worker.fetch(request, env);

  assert.equal(response.status, 409);
  assert.equal(env.DB.sessions.get(invitation.sessionId).claimed_at, null);
});


function confirmationMessage(session) {
  return [
    "DCH-PAIRING-CONFIRM-V1",
    session.id,
    session.recipient_device_id,
    session.initiator_ephemeral_public_key,
    session.recipient_ephemeral_public_key,
    String(session.claimed_at),
    String(session.confirmation_expires_at),
    "1"
  ].join("\n");
}

async function signConfirmation(privateKey, session) {
  const raw = new Uint8Array(
    await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      privateKey,
      new TextEncoder().encode(confirmationMessage(session))
    )
  );

  return rawEcdsaToDer(raw).toString("base64");
}

async function claimForConfirmation(setup) {
  const claimResponse = await worker.fetch(
    setup.request.clone(),
    setup.env
  );
  assert.equal(claimResponse.status, 200);

  return setup.env.DB.sessions.get(setup.invitation.sessionId);
}

test("initiator confirms a claimed pairing and activates the device pair", async () => {
  const setup = await setupClaim();
  const session = await claimForConfirmation(setup);
  const signature = await signConfirmation(
    setup.initiatorSigning.privateKey,
    session
  );

  const request = new Request(
    `https://device-sync.test/v1/pairings/${session.id}/confirm`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`
      },
      body: JSON.stringify({ signature })
    }
  );

  const response = await worker.fetch(request, setup.env);
  const data = await response.json();

  assert.equal(response.status, 200);
  assert.equal(data.state, "confirmed");
  assert.equal(session.confirmed_at, data.confirmedAt);
  assert.equal(setup.env.DB.pairs.size, 1);

  const pair = [...setup.env.DB.pairs.values()][0];
  assert.equal(
    pair.device_a_id,
    [setup.env.DB.devices[0].id, recipientId].sort()[0]
  );
  assert.equal(
    pair.device_b_id,
    [setup.env.DB.devices[0].id, recipientId].sort()[1]
  );
});

test("rejects an invalid initiator confirmation signature without activating a pair", async () => {
  const setup = await setupClaim();
  const session = await claimForConfirmation(setup);

  const request = new Request(
    `https://device-sync.test/v1/pairings/${session.id}/confirm`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`
      },
      body: JSON.stringify({
        signature: Buffer.from("invalid signature").toString("base64")
      })
    }
  );

  const response = await worker.fetch(request, setup.env);

  assert.equal(response.status, 401);
  assert.equal(session.confirmed_at ?? null, null);
  assert.equal(setup.env.DB.pairs.size, 0);
});
