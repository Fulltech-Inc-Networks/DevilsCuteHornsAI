import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { webcrypto, createHash } from "node:crypto";

const crypto = webcrypto;
const encoder = new TextEncoder();

class MockD1 {
  constructor() {
    this.challenges = new Map();
    this.devices = [];
  }

  prepare(sql) {
    const statement = sql.replace(/\s+/g, " ").trim();

    return {
      bind: (...params) => ({
        run: async () => {
          if (statement.startsWith("INSERT INTO device_registration_challenges")) {
            const [id, challengeHash, expiresAt, createdAt] = params;

            this.challenges.set(id, {
              id,
              challengeHash,
              expiresAt,
              createdAt,
              consumedAt: null
            });

            return { meta: { changes: 1 } };
          }

          if (statement.startsWith("UPDATE device_registration_challenges")) {
            const [now, id, challengeHash, expiryCheck] = params;
            const row = this.challenges.get(id);

            if (
              row &&
              row.challengeHash === challengeHash &&
              row.consumedAt === null &&
              row.expiresAt > expiryCheck
            ) {
              row.consumedAt = now;
              return { meta: { changes: 1 } };
            }

            return { meta: { changes: 0 } };
          }

          if (statement.startsWith("INSERT INTO devices")) {
            const [
              id,
              name,
              publicKey,
              tokenHash,
              createdAt,
              signingPublicKey,
              encryptionPublicKey
            ] = params;

            this.devices.push({
              id,
              name,
              publicKey,
              tokenHash,
              createdAt,
              signingPublicKey,
              encryptionPublicKey
            });

            return { meta: { changes: 1 } };
          }

          throw new Error(`Unexpected SQL in test: ${statement}`);
        }
      })
    };
  }
}

function makeEnvironment() {
  return { DB: new MockD1() };
}

function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}

function rawSignatureToDer(raw) {
  assert.equal(raw.length, 64, "Expected a 64-byte P-256 signature");

  function encodeInteger(input) {
    let start = 0;

    while (start < input.length - 1 && input[start] === 0) {
      start++;
    }

    let value = input.slice(start);

    if (value[0] & 0x80) {
      value = Buffer.concat([Buffer.from([0]), Buffer.from(value)]);
    }

    return Buffer.concat([
      Buffer.from([0x02, value.length]),
      Buffer.from(value)
    ]);
  }

  const r = encodeInteger(raw.slice(0, 32));
  const s = encodeInteger(raw.slice(32, 64));
  const content = Buffer.concat([r, s]);

  return Buffer.concat([
    Buffer.from([0x30, content.length]),
    content
  ]);
}

async function createSignedRegistration(env, deviceName = "Study Phone") {
  const challengeResponse = await worker.fetch(
    new Request("https://device-sync.test/v1/registration/challenge", {
      method: "POST"
    }),
    env
  );

  assert.equal(challengeResponse.status, 201);

  const challengeData = await challengeResponse.json();

  const signingPair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"]
  );

  const encryptionPair = await crypto.subtle.generateKey(
    { name: "ECDH", namedCurve: "P-256" },
    true,
    ["deriveBits"]
  );

  const signingPublicBytes = new Uint8Array(
    await crypto.subtle.exportKey("spki", signingPair.publicKey)
  );

  const encryptionPublicBytes = new Uint8Array(
    await crypto.subtle.exportKey("raw", encryptionPair.publicKey)
  );

  const signingPublicKey = Buffer.from(signingPublicBytes).toString("base64");
  const encryptionPublicKey = Buffer.from(encryptionPublicBytes).toString("base64");

  const normalizedName = deviceName.normalize("NFC").trim();

  const message = [
    "DCH-DEVICE-REGISTRATION-V1",
    challengeData.challengeId,
    challengeData.challenge,
    normalizedName,
    signingPublicKey,
    encryptionPublicKey,
    "P-256"
  ].join("\n");

  const rawSignature = new Uint8Array(
    await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      signingPair.privateKey,
      encoder.encode(message)
    )
  );

  const signature = rawSignatureToDer(rawSignature).toString("base64");

  return {
    challengeData,
    body: {
      challengeId: challengeData.challengeId,
      challenge: challengeData.challenge,
      deviceName,
      signingPublicKey,
      encryptionPublicKey,
      signature
    }
  };
}

function registrationRequest(body) {
  return new Request("https://device-sync.test/v1/registration", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
}

test("issues a unique registration challenge and stores its hash", async () => {
  const env = makeEnvironment();

  const response = await worker.fetch(
    new Request("https://device-sync.test/v1/registration/challenge", {
      method: "POST"
    }),
    env
  );

  assert.equal(response.status, 201);

  const data = await response.json();
  assert.equal(data.algorithm, "ECDSA-P256-SHA256");
  assert.equal(typeof data.challengeId, "string");
  assert.equal(typeof data.challenge, "string");
  assert.equal(Buffer.from(data.challenge, "base64").length, 32);

  const stored = env.DB.challenges.get(data.challengeId);
  assert.ok(stored);
  assert.equal(
    stored.challengeHash,
    sha256Hex(Buffer.from(data.challenge, "base64"))
  );
  assert.equal(stored.consumedAt, null);
});

test("registers a device and stores only the bearer-token hash", async () => {
  const env = makeEnvironment();
  const { body } = await createSignedRegistration(env, "  Study Phone  ");

  const response = await worker.fetch(registrationRequest(body), env);
  assert.equal(response.status, 201);

  const data = await response.json();

  assert.equal(data.deviceName, "Study Phone");
  assert.equal(data.tokenType, "Bearer");
  assert.equal(data.keyAlgorithm, "P-256");
  assert.match(data.deviceToken, /^[A-Za-z0-9_-]{40,200}$/);
  assert.equal(env.DB.devices.length, 1);

  const stored = env.DB.devices[0];

  assert.equal(stored.id, data.deviceId);
  assert.equal(stored.name, "Study Phone");
  assert.equal(stored.tokenHash, sha256Hex(data.deviceToken));
  assert.notEqual(stored.tokenHash, data.deviceToken);
  assert.equal("deviceToken" in stored, false);

  const challenge = env.DB.challenges.get(body.challengeId);
  assert.ok(challenge.consumedAt);
});

test("rejects a registration with an invalid signature", async () => {
  const env = makeEnvironment();
  const { body } = await createSignedRegistration(env);

  const signatureBytes = Buffer.from(body.signature, "base64");
  signatureBytes[signatureBytes.length - 1] ^= 1;
  body.signature = signatureBytes.toString("base64");

  const response = await worker.fetch(registrationRequest(body), env);

  assert.equal(response.status, 401);
  assert.equal(env.DB.devices.length, 0);

  const challenge = env.DB.challenges.get(body.challengeId);
  assert.equal(challenge.consumedAt, null);
});

test("rejects an expired registration challenge", async () => {
  const env = makeEnvironment();
  const { body } = await createSignedRegistration(env);

  const challenge = env.DB.challenges.get(body.challengeId);
  challenge.expiresAt = Date.now() - 1000;

  const response = await worker.fetch(registrationRequest(body), env);

  assert.equal(response.status, 409);
  assert.equal(env.DB.devices.length, 0);
});

test("rejects reuse of an already-consumed challenge", async () => {
  const env = makeEnvironment();
  const { body } = await createSignedRegistration(env);

  const first = await worker.fetch(registrationRequest(body), env);
  assert.equal(first.status, 201);

  const second = await worker.fetch(registrationRequest(body), env);
  assert.equal(second.status, 409);

  assert.equal(env.DB.devices.length, 1);
});

test("rejects malformed registration JSON", async () => {
  const env = makeEnvironment();

  const response = await worker.fetch(
    new Request("https://device-sync.test/v1/registration", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{invalid-json"
    }),
    env
  );

  assert.equal(response.status, 400);
  assert.equal(env.DB.devices.length, 0);
});

test("rejects registration bodies larger than 16 KB", async () => {
  const env = makeEnvironment();

  const response = await worker.fetch(
    new Request("https://device-sync.test/v1/registration", {
      method: "POST",
      body: "x".repeat(16_385)
    }),
    env
  );

  assert.equal(response.status, 413);
  assert.equal(env.DB.devices.length, 0);
});

test("returns 503 when challenge storage fails", async () => {
  const env = makeEnvironment();
  const originalPrepare = env.DB.prepare.bind(env.DB);

  env.DB.prepare = (sql) => {
    if (sql.includes("INSERT INTO device_registration_challenges")) {
      return {
        bind: () => ({
          run: async () => {
            throw new Error("Simulated database failure");
          }
        })
      };
    }

    return originalPrepare(sql);
  };

  const response = await worker.fetch(
    new Request("https://device-sync.test/v1/registration/challenge", {
      method: "POST"
    }),
    env
  );

  assert.equal(response.status, 503);
  assert.equal(env.DB.challenges.size, 0);
});

test("consumes the challenge when device insertion fails", async () => {
  const env = makeEnvironment();
  const { body } = await createSignedRegistration(env);

  const originalPrepare = env.DB.prepare.bind(env.DB);

  env.DB.prepare = (sql) => {
    if (sql.includes("INSERT INTO devices")) {
      return {
        bind: () => ({
          run: async () => {
            throw new Error("Simulated device insert failure");
          }
        })
      };
    }

    return originalPrepare(sql);
  };

  const response = await worker.fetch(registrationRequest(body), env);

  assert.equal(response.status, 503);
  assert.equal(env.DB.devices.length, 0);

  const challenge = env.DB.challenges.get(body.challengeId);
  assert.ok(challenge.consumedAt);

  env.DB.prepare = originalPrepare;

  const retry = await worker.fetch(registrationRequest(body), env);
  assert.equal(retry.status, 409);
});

test("rejects device names exceeding 128 UTF-8 bytes", async () => {
  const env = makeEnvironment();
  const { body } = await createSignedRegistration(env);

  body.deviceName = "A".repeat(129);

  const response = await worker.fetch(registrationRequest(body), env);

  assert.equal(response.status, 400);
  assert.equal(env.DB.devices.length, 0);

  const challenge = env.DB.challenges.get(body.challengeId);
  assert.equal(challenge.consumedAt, null);
});
