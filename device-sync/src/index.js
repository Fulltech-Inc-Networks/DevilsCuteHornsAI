const MAX_CIPHERTEXT_BYTES = 2 * 1024 * 1024;
const MAX_TRANSFER_AGE_MS = 48 * 60 * 60 * 1000;

const JSON_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store"
};

function response(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: JSON_HEADERS
  });
}

function error(message, status = 400) {
  return response({ error: message }, status);
}

function isUuid(value) {
  return typeof value === "string" &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function decodeBase64(value) {
  if (typeof value !== "string" || value.length > 3_000_000) {
    throw new Error("Invalid encoded payload.");
  }

  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);

  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }

  return bytes;
}

function encodeBase64(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

async function sha256Hex(value) {
  const digest = await crypto.subtle.digest("SHA-256", value);
  return Array.from(new Uint8Array(digest))
    .map(byte => byte.toString(16).padStart(2, "0"))
    .join("");
}


const REGISTRATION_CHALLENGE_AGE_MS = 5 * 60 * 1000;

function base64Url(bytes) {
  return encodeBase64(bytes)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function derEcdsaToRaw(signature) {
  // Android's ECDSA Signature API returns ASN.1 DER.
  // Web Crypto ECDSA verification expects 32-byte r || 32-byte s.
  if (
    signature.length < 8 ||
    signature.length > 80 ||
    signature[0] !== 0x30
  ) {
    throw new Error("Invalid ECDSA signature encoding.");
  }

  let offset = 1;
  const sequenceLength = signature[offset++];

  if (
    sequenceLength & 0x80 ||
    sequenceLength !== signature.length - offset
  ) {
    throw new Error("Invalid ECDSA signature sequence.");
  }

  function readInteger() {
    if (signature[offset++] !== 0x02) {
      throw new Error("Invalid ECDSA signature integer.");
    }

    const length = signature[offset++];

    if (
      !length ||
      length > 33 ||
      offset + length > signature.length
    ) {
      throw new Error("Invalid ECDSA integer length.");
    }

    let value = signature.slice(offset, offset + length);
    offset += length;

    if (value[0] & 0x80) {
      throw new Error("Negative ECDSA integer.");
    }

    if (value.length > 1 && value[0] === 0) {
      if (!(value[1] & 0x80)) {
        throw new Error("Non-canonical ECDSA integer.");
      }
      value = value.slice(1);
    }

    if (value.length > 32) {
      throw new Error("ECDSA integer is too large.");
    }

    const padded = new Uint8Array(32);
    padded.set(value, 32 - value.length);
    return padded;
  }

  const r = readInteger();
  const s = readInteger();

  if (offset !== signature.length) {
    throw new Error("Unexpected ECDSA signature data.");
  }

  const raw = new Uint8Array(64);
  raw.set(r, 0);
  raw.set(s, 32);
  return raw;
}

function registrationMessage({
  challengeId,
  challenge,
  deviceName,
  signingPublicKey,
  encryptionPublicKey
}) {
  return [
    "DCH-DEVICE-REGISTRATION-V1",
    challengeId,
    challenge,
    deviceName,
    signingPublicKey,
    encryptionPublicKey,
    "P-256"
  ].join("\n");
}

async function handleRegistrationChallenge(env) {
  const challengeId = crypto.randomUUID();
  const challengeBytes = crypto.getRandomValues(new Uint8Array(32));
  const challenge = encodeBase64(challengeBytes);
  const challengeHash = await sha256Hex(challengeBytes);
  const now = Date.now();
  const expiresAt = now + REGISTRATION_CHALLENGE_AGE_MS;

  try {
    await env.DB.prepare(`
      INSERT INTO device_registration_challenges (
        id, challenge_hash, expires_at, created_at
      ) VALUES (?, ?, ?, ?)
    `).bind(challengeId, challengeHash, expiresAt, now).run();
  } catch (err) {
    console.error("Registration challenge creation failed:", err);
    return error("A registration challenge could not be created.", 503);
  }

  return response({
    challengeId,
    challenge,
    algorithm: "ECDSA-P256-SHA256",
    expiresAt
  }, 201);
}

async function handleDeviceRegistration(request, env) {
  const declaredLength = Number(request.headers.get("Content-Length") || 0);

  if (declaredLength > 16_384) {
    return error("Registration request is too large.", 413);
  }

  const reader = request.body?.getReader();

  if (!reader) {
    return error("Registration request body is required.");
  }

  const chunks = [];
  let totalBytes = 0;
  let body;

  try {
    while (true) {
      const { done, value } = await reader.read();

      if (done) break;

      totalBytes += value.byteLength;

      if (totalBytes > 16_384) {
        try {
          await reader.cancel();
        } catch {}

        return error("Registration request is too large.", 413);
      }

      chunks.push(value);
    }

    const bodyBytes = new Uint8Array(totalBytes);
    let offset = 0;

    for (const chunk of chunks) {
      bodyBytes.set(chunk, offset);
      offset += chunk.byteLength;
    }

    body = JSON.parse(
      new TextDecoder("utf-8", { fatal: true }).decode(bodyBytes)
    );
  } catch {
    return error("Invalid registration JSON.");
  }

  const {
    challengeId,
    challenge,
    deviceName,
    signingPublicKey,
    encryptionPublicKey,
    signature
  } = body || {};

  if (
    !isUuid(challengeId) ||
    typeof challenge !== "string" ||
    typeof deviceName !== "string" ||
    typeof signingPublicKey !== "string" ||
    typeof encryptionPublicKey !== "string" ||
    typeof signature !== "string"
  ) {
    return error("Missing or invalid registration fields.");
  }

  const normalizedName = deviceName.normalize("NFC").trim();

  if (
    normalizedName.length < 1 ||
    new TextEncoder().encode(normalizedName).length > 128
  ) {
    return error("Device name must contain 1 to 128 UTF-8 bytes.");
  }

  let challengeBytes;
  let signingBytes;
  let encryptionBytes;
  let signatureBytes;

  try {
    challengeBytes = decodeBase64(challenge);
    signingBytes = decodeBase64(signingPublicKey);
    encryptionBytes = decodeBase64(encryptionPublicKey);
    signatureBytes = decodeBase64(signature);

    if (
      encodeBase64(challengeBytes) !== challenge ||
      encodeBase64(signingBytes) !== signingPublicKey ||
      encodeBase64(encryptionBytes) !== encryptionPublicKey ||
      encodeBase64(signatureBytes) !== signature ||
      challengeBytes.length !== 32 ||
      encryptionBytes.length !== 65 ||
      encryptionBytes[0] !== 0x04
    ) {
      return error("Invalid registration key or challenge encoding.");
    }
  } catch {
    return error("Invalid registration key or challenge encoding.");
  }

  let verified = false;

  try {
    const signingKey = await crypto.subtle.importKey(
      "spki",
      signingBytes,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"]
    );

    // Importing the encryption point also checks that it is a valid
    // P-256 public key before it is stored.
    await crypto.subtle.importKey(
      "raw",
      encryptionBytes,
      { name: "ECDH", namedCurve: "P-256" },
      false,
      []
    );

    const message = registrationMessage({
      challengeId,
      challenge,
      deviceName: normalizedName,
      signingPublicKey,
      encryptionPublicKey
    });

    verified = await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      signingKey,
      derEcdsaToRaw(signatureBytes),
      new TextEncoder().encode(message)
    );
  } catch {
    verified = false;
  }

  if (!verified) {
    return error("Device registration signature verification failed.", 401);
  }

  const challengeHash = await sha256Hex(challengeBytes);
  const now = Date.now();

  // Consume the challenge with a conditional update. A replay cannot
  // consume it again. If insertion subsequently fails, request a new
  // challenge rather than reusing this one.
  let consumed;

  try {
    consumed = await env.DB.prepare(`
      UPDATE device_registration_challenges
      SET consumed_at = ?
      WHERE id = ?
        AND challenge_hash = ?
        AND consumed_at IS NULL
        AND expires_at > ?
    `).bind(now, challengeId, challengeHash, now).run();
  } catch (err) {
    console.error("Registration challenge consumption failed:", err);
    return error("Registration could not be completed. Please retry.", 503);
  }

  if ((consumed.meta?.changes || 0) !== 1) {
    return error("Registration challenge is invalid, expired, or already used.", 409);
  }

  const deviceId = crypto.randomUUID();
  const tokenBytes = crypto.getRandomValues(new Uint8Array(32));
  const deviceToken = base64Url(tokenBytes);
  const tokenHash = await sha256Hex(new TextEncoder().encode(deviceToken));

  try {
    await env.DB.prepare(`
      INSERT INTO devices (
        id,
        name,
        public_key,
        key_algorithm,
        token_hash,
        created_at,
        signing_public_key,
        encryption_public_key
      ) VALUES (?, ?, ?, 'ECDH-P256', ?, ?, ?, ?)
    `).bind(
      deviceId,
      normalizedName,
      encryptionPublicKey,
      tokenHash,
      now,
      signingPublicKey,
      encryptionPublicKey
    ).run();
  } catch (err) {
    console.error("Device registration database insert failed:", err);
    return error("Device registration could not be saved. Request a new challenge.", 503);
  }

  return response({
    deviceId,
    deviceName: normalizedName,
    deviceToken,
    tokenType: "Bearer",
    keyAlgorithm: "P-256",
    registeredAt: now
  }, 201);
}



const PAIRING_INVITATION_AGE_MS = 5 * 60 * 1000;
const PAIRING_CONFIRMATION_AGE_MS = 2 * 60 * 1000;

async function readLimitedJson(request, maxBytes = 16_384) {
  const declaredLength = Number(request.headers.get("Content-Length") || 0);

  if (declaredLength > maxBytes) {
    return { error: error("Request body is too large.", 413) };
  }

  const reader = request.body?.getReader();

  if (!reader) {
    return { error: error("Request body is required.") };
  }

  const chunks = [];
  let totalBytes = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      totalBytes += value.byteLength;

      if (totalBytes > maxBytes) {
        try {
          await reader.cancel();
        } catch {}

        return { error: error("Request body is too large.", 413) };
      }

      chunks.push(value);
    }

    const bytes = new Uint8Array(totalBytes);
    let offset = 0;

    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }

    return {
      body: JSON.parse(
        new TextDecoder("utf-8", { fatal: true }).decode(bytes)
      )
    };
  } catch {
    return { error: error("Invalid JSON request body.") };
  }
}

async function validateP256PublicKey(encoded) {
  try {
    const bytes = decodeBase64(encoded);

    if (
      encodeBase64(bytes) !== encoded ||
      bytes.length !== 65 ||
      bytes[0] !== 0x04
    ) {
      return false;
    }

    await crypto.subtle.importKey(
      "raw",
      bytes,
      { name: "ECDH", namedCurve: "P-256" },
      false,
      []
    );

    return true;
  } catch {
    return false;
  }
}

async function handleCreatePairingInvitation(request, env, initiator) {
  const parsed = await readLimitedJson(request);

  if (parsed.error) return parsed.error;

  const {
    invitationSecret,
    initiatorEphemeralPublicKey
  } = parsed.body || {};

  if (
    typeof invitationSecret !== "string" ||
    typeof initiatorEphemeralPublicKey !== "string"
  ) {
    return error("Missing or invalid pairing invitation fields.");
  }

  let secretBytes;

  try {
    secretBytes = decodeBase64(invitationSecret);

    if (
      encodeBase64(secretBytes) !== invitationSecret ||
      secretBytes.length !== 32
    ) {
      return error("Invitation secret must be 32 random bytes.");
    }
  } catch {
    return error("Invalid invitation secret encoding.");
  }

  if (!await validateP256PublicKey(initiatorEphemeralPublicKey)) {
    return error("Invalid P-256 invitation public key.");
  }

  const sessionId = crypto.randomUUID();
  const now = Date.now();
  const expiresAt = now + PAIRING_INVITATION_AGE_MS;
  const invitationHash = await sha256Hex(secretBytes);

  try {
    await env.DB.prepare(`
      INSERT INTO pairing_sessions (
        id,
        invitation_hash,
        initiator_device_id,
        initiator_ephemeral_public_key,
        created_at,
        expires_at
      ) VALUES (?, ?, ?, ?, ?, ?)
    `).bind(
      sessionId,
      invitationHash,
      initiator.id,
      initiatorEphemeralPublicKey,
      now,
      expiresAt
    ).run();
  } catch (err) {
    console.error("Pairing invitation creation failed:", err);
    return error("Pairing invitation could not be created.", 503);
  }

  return response({
    sessionId,
    initiatorDeviceId: initiator.id,
    initiatorDeviceName: initiator.name,
    initiatorEphemeralPublicKey,
    protocolVersion: 1,
    expiresAt
  }, 201);
}


function pairingClaimMessage({
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

async function handleClaimPairing(request, env, recipient, sessionId) {
  if (!isUuid(sessionId)) {
    return error("Invalid pairing session.", 400);
  }

  const parsed = await readLimitedJson(request);

  if (parsed.error) return parsed.error;

  const {
    invitationSecret,
    recipientEphemeralPublicKey,
    signature
  } = parsed.body || {};

  if (
    typeof invitationSecret !== "string" ||
    typeof recipientEphemeralPublicKey !== "string" ||
    typeof signature !== "string"
  ) {
    return error("Missing or invalid pairing claim fields.");
  }

  let secretBytes;
  let signatureBytes;

  try {
    secretBytes = decodeBase64(invitationSecret);
    signatureBytes = decodeBase64(signature);

    if (
      encodeBase64(secretBytes) !== invitationSecret ||
      secretBytes.length !== 32 ||
      encodeBase64(signatureBytes) !== signature ||
      signatureBytes.length < 8 ||
      signatureBytes.length > 80
    ) {
      return error("Invalid invitation secret or signature encoding.");
    }
  } catch {
    return error("Invalid invitation secret or signature encoding.");
  }

  if (!await validateP256PublicKey(recipientEphemeralPublicKey)) {
    return error("Invalid recipient P-256 public key.");
  }

  let session;

  try {
    session = await env.DB.prepare(`
      SELECT
        ps.id,
        ps.invitation_hash,
        ps.initiator_device_id,
        ps.initiator_ephemeral_public_key,
        ps.expires_at,
        ps.consumed_at,
        ps.claimed_at,
        d.name AS initiator_device_name,
        d.revoked_at AS initiator_revoked_at
      FROM pairing_sessions ps
      JOIN devices d ON d.id = ps.initiator_device_id
      WHERE ps.id = ?
      LIMIT 1
    `).bind(sessionId).first();
  } catch (err) {
    console.error("Pairing claim lookup failed:", err);
    return error("Pairing claim could not be processed.", 503);
  }

  if (!session) {
    return error("Pairing invitation is unavailable.", 404);
  }

  const now = Date.now();

  if (
    session.initiator_device_id === recipient.id ||
    session.initiator_revoked_at != null ||
    session.consumed_at !== null ||
    session.claimed_at !== null ||
    session.expires_at <= now
  ) {
    return error("Pairing invitation is expired or unavailable.", 409);
  }

  const invitationHash = await sha256Hex(secretBytes);

  if (invitationHash !== session.invitation_hash) {
    return error("Pairing invitation is expired or unavailable.", 409);
  }

  if (!recipient.signing_public_key) {
    return error("Recipient signing key is unavailable.", 401);
  }

  let verified = false;

  try {
    const signingKeyBytes = decodeBase64(recipient.signing_public_key);

    if (encodeBase64(signingKeyBytes) !== recipient.signing_public_key) {
      return error("Recipient signing key is invalid.", 401);
    }

    const signingKey = await crypto.subtle.importKey(
      "spki",
      signingKeyBytes,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"]
    );

    const message = pairingClaimMessage({
      sessionId,
      invitationSecret,
      initiatorEphemeralPublicKey: session.initiator_ephemeral_public_key,
      recipientEphemeralPublicKey,
      recipientDeviceId: recipient.id,
      expiresAt: session.expires_at
    });

    verified = await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      signingKey,
      derEcdsaToRaw(signatureBytes),
      new TextEncoder().encode(message)
    );
  } catch {
    verified = false;
  }

  if (!verified) {
    return error("Pairing claim signature verification failed.", 401);
  }

  const claimedAt = Date.now();
  const confirmationExpiresAt =
    claimedAt + PAIRING_CONFIRMATION_AGE_MS;

  try {
    const result = await env.DB.prepare(`
      UPDATE pairing_sessions
      SET
        consumed_at = ?,
        recipient_device_id = ?,
        claimed_at = ?,
        confirmation_expires_at = ?,
        recipient_ephemeral_public_key = ?
      WHERE id = ?
        AND invitation_hash = ?
        AND expires_at = ?
        AND expires_at > ?
        AND consumed_at IS NULL
        AND claimed_at IS NULL
        AND recipient_device_id IS NULL
        AND EXISTS (
          SELECT 1
          FROM devices
          WHERE devices.id = pairing_sessions.initiator_device_id
            AND devices.revoked_at IS NULL
        )
    `).bind(
      claimedAt,
      recipient.id,
      claimedAt,
      confirmationExpiresAt,
      recipientEphemeralPublicKey,
      sessionId,
      invitationHash,
      session.expires_at,
      claimedAt
    ).run();

    if ((result.meta?.changes || 0) !== 1) {
      return error("Pairing invitation was already claimed or expired.", 409);
    }
  } catch (err) {
    console.error("Pairing claim update failed:", err);
    return error("Pairing claim could not be saved.", 503);
  }

  return response({
    sessionId,
    state: "claimed",
    initiatorDeviceId: session.initiator_device_id,
    initiatorDeviceName: session.initiator_device_name,
    initiatorEphemeralPublicKey:
      session.initiator_ephemeral_public_key,
    recipientDeviceId: recipient.id,
    recipientDeviceName: recipient.name,
    recipientEphemeralPublicKey,
    claimedAt,
    confirmationExpiresAt,
    protocolVersion: 1
  }, 200);
}



function pairingConfirmationMessage(session) {
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

async function getPairingSession(env, sessionId) {
  return env.DB.prepare(`
    SELECT
      ps.id,
      ps.initiator_device_id,
      ps.initiator_ephemeral_public_key,
      ps.expires_at,
      ps.claimed_at,
      ps.confirmation_expires_at,
      ps.confirmed_at,
      ps.recipient_device_id,
      ps.recipient_ephemeral_public_key,
      recipient.name AS recipient_device_name,
      recipient.revoked_at AS recipient_revoked_at,
      initiator.revoked_at AS initiator_revoked_at
    FROM pairing_sessions ps
    JOIN devices initiator
      ON initiator.id = ps.initiator_device_id
    LEFT JOIN devices recipient
      ON recipient.id = ps.recipient_device_id
    WHERE ps.id = ?
    LIMIT 1
  `).bind(sessionId).first();
}

async function handleGetPairing(env, initiator, sessionId) {
  if (!isUuid(sessionId)) {
    return error("Invalid pairing session.", 400);
  }

  let session;

  try {
    session = await getPairingSession(env, sessionId);
  } catch (err) {
    console.error("Pairing status lookup failed:", err);
    return error("Pairing status could not be retrieved.", 503);
  }

  if (!session || session.initiator_device_id !== initiator.id) {
    return error("Pairing session not found.", 404);
  }

  const now = Date.now();

  if (session.claimed_at === null) {
    return response({
      sessionId,
      state: session.expires_at <= now ? "expired" : "pending",
      expiresAt: session.expires_at,
      protocolVersion: 1
    });
  }

  if (
    session.initiator_revoked_at != null ||
    session.recipient_revoked_at != null ||
    !session.recipient_device_id
  ) {
    return error("A device in this pairing is unavailable.", 409);
  }

  if (
    session.confirmed_at === null &&
    session.confirmation_expires_at <= now
  ) {
    return response({
      sessionId,
      state: "expired",
      claimedAt: session.claimed_at,
      confirmationExpiresAt: session.confirmation_expires_at,
      protocolVersion: 1
    });
  }

  return response({
    sessionId,
    state: session.confirmed_at === null ? "claimed" : "confirmed",
    initiatorDeviceId: session.initiator_device_id,
    initiatorEphemeralPublicKey:
      session.initiator_ephemeral_public_key,
    recipientDeviceId: session.recipient_device_id,
    recipientDeviceName: session.recipient_device_name,
    recipientEphemeralPublicKey:
      session.recipient_ephemeral_public_key,
    claimedAt: session.claimed_at,
    confirmationExpiresAt: session.confirmation_expires_at,
    confirmedAt: session.confirmed_at,
    protocolVersion: 1
  });
}

async function handleConfirmPairing(request, env, initiator, sessionId) {
  if (!isUuid(sessionId)) {
    return error("Invalid pairing session.", 400);
  }

  const parsed = await readLimitedJson(request);
  if (parsed.error) return parsed.error;

  const { signature } = parsed.body || {};

  if (typeof signature !== "string") {
    return error("Missing pairing confirmation signature.");
  }

  let signatureBytes;

  try {
    signatureBytes = decodeBase64(signature);

    if (
      encodeBase64(signatureBytes) !== signature ||
      signatureBytes.length < 8 ||
      signatureBytes.length > 80
    ) {
      return error("Invalid confirmation signature encoding.");
    }
  } catch {
    return error("Invalid confirmation signature encoding.");
  }

  let session;

  try {
    session = await getPairingSession(env, sessionId);
  } catch (err) {
    console.error("Pairing confirmation lookup failed:", err);
    return error("Pairing confirmation could not be processed.", 503);
  }

  if (!session || session.initiator_device_id !== initiator.id) {
    return error("Pairing session not found.", 404);
  }

  const now = Date.now();

  if (
    session.claimed_at === null ||
    session.confirmed_at !== null ||
    !session.recipient_device_id ||
    session.confirmation_expires_at <= now ||
    session.initiator_revoked_at != null ||
    session.recipient_revoked_at != null
  ) {
    return error("Pairing claim is unavailable or expired.", 409);
  }

  if (!initiator.signing_public_key) {
    return error("Initiator signing key is unavailable.", 401);
  }

  let verified = false;

  try {
    const keyBytes = decodeBase64(initiator.signing_public_key);

    if (encodeBase64(keyBytes) !== initiator.signing_public_key) {
      return error("Initiator signing key is invalid.", 401);
    }

    const signingKey = await crypto.subtle.importKey(
      "spki",
      keyBytes,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"]
    );

    verified = await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      signingKey,
      derEcdsaToRaw(signatureBytes),
      new TextEncoder().encode(pairingConfirmationMessage(session))
    );
  } catch {
    verified = false;
  }

  if (!verified) {
    return error("Pairing confirmation signature verification failed.", 401);
  }

  const deviceA = initiator.id < session.recipient_device_id
    ? initiator.id
    : session.recipient_device_id;

  const deviceB = initiator.id < session.recipient_device_id
    ? session.recipient_device_id
    : initiator.id;

  const confirmedAt = Date.now();

  try {
    const results = await env.DB.batch([
      env.DB.prepare(`
        UPDATE pairing_sessions
        SET confirmed_at = ?
        WHERE id = ?
          AND initiator_device_id = ?
          AND recipient_device_id = ?
          AND claimed_at = ?
          AND confirmation_expires_at > ?
          AND confirmed_at IS NULL
          AND EXISTS (
            SELECT 1 FROM devices
            WHERE devices.id = ?
              AND devices.revoked_at IS NULL
          )
          AND EXISTS (
            SELECT 1 FROM devices
            WHERE devices.id = ?
              AND devices.revoked_at IS NULL
          )
      `).bind(
        confirmedAt,
        sessionId,
        initiator.id,
        session.recipient_device_id,
        session.claimed_at,
        confirmedAt,
        initiator.id,
        session.recipient_device_id
      ),

      env.DB.prepare(`
        INSERT INTO device_pairs (
          id, device_a_id, device_b_id, created_at
        )
        SELECT ?, ?, ?, ?
        WHERE EXISTS (
          SELECT 1 FROM pairing_sessions
          WHERE id = ?
            AND initiator_device_id = ?
            AND recipient_device_id = ?
            AND confirmed_at = ?
        )
        ON CONFLICT(device_a_id, device_b_id)
        DO UPDATE SET revoked_at = NULL
      `).bind(
        crypto.randomUUID(),
        deviceA,
        deviceB,
        confirmedAt,
        sessionId,
        initiator.id,
        session.recipient_device_id,
        confirmedAt
      )
    ]);

    if ((results[0]?.meta?.changes || 0) !== 1) {
      return error("Pairing claim expired or is no longer available.", 409);
    }
  } catch (err) {
    console.error("Pairing confirmation transaction failed:", err);
    return error("Pairing could not be activated.", 503);
  }

  return response({
    sessionId,
    state: "confirmed",
    initiatorDeviceId: initiator.id,
    recipientDeviceId: session.recipient_device_id,
    confirmedAt,
    protocolVersion: 1
  });
}

async function authenticate(request, env) {
  const header = request.headers.get("Authorization") || "";
  const match = header.match(/^Bearer ([A-Za-z0-9_-]{40,200})$/);

  if (!match) return null;

  const tokenHash = await sha256Hex(
    new TextEncoder().encode(match[1])
  );

  const device = await env.DB.prepare(`
    SELECT id, name, public_key, signing_public_key,
           encryption_public_key
    FROM devices
    WHERE token_hash = ? AND revoked_at IS NULL
    LIMIT 1
  `).bind(tokenHash).first();

  if (device) {
    await env.DB.prepare(`
      UPDATE devices SET last_seen_at = ? WHERE id = ?
    `).bind(Date.now(), device.id).run();
  }

  return device || null;
}


async function areDevicesPaired(env, firstDeviceId, secondDeviceId) {
  const deviceA = firstDeviceId < secondDeviceId
    ? firstDeviceId
    : secondDeviceId;

  const deviceB = firstDeviceId < secondDeviceId
    ? secondDeviceId
    : firstDeviceId;

  const pair = await env.DB.prepare(`
    SELECT id
    FROM device_pairs
    WHERE device_a_id = ?
      AND device_b_id = ?
      AND revoked_at IS NULL
    LIMIT 1
  `).bind(deviceA, deviceB).first();

  return Boolean(pair);
}


async function handleCreateBatch(request, env, sender) {
  // Discover every actively paired, non-revoked recipient.
  const result = await env.DB.prepare(`
    SELECT
      d.id,
      d.name,
      d.public_key,
      d.key_algorithm
    FROM device_pairs p
    JOIN devices d
      ON d.id = CASE
        WHEN p.device_a_id = ? THEN p.device_b_id
        ELSE p.device_a_id
      END
    WHERE (p.device_a_id = ? OR p.device_b_id = ?)
      AND p.revoked_at IS NULL
      AND d.revoked_at IS NULL
    ORDER BY d.id
    LIMIT 21
  `).bind(sender.id, sender.id, sender.id).all();

  const recipients = result.results || [];

  if (recipients.length === 0) {
    return error("No active paired recipient devices are available.", 409);
  }

  if (recipients.length > 20) {
    return error(
      "This device has more than 20 active paired recipients. No batch was created.",
      409
    );
  }

  const now = Date.now();
  const batchId = crypto.randomUUID();
  const expiresAt = now + MAX_TRANSFER_AGE_MS;

  const statements = [
    env.DB.prepare(`
      INSERT INTO transfer_batches (
        id, sender_device_id, expected_recipients,
        created_at, expires_at, status
      ) VALUES (?, ?, ?, ?, ?, 'pending')
    `).bind(batchId, sender.id, recipients.length, now, expiresAt)
  ];

  for (const recipient of recipients) {
    statements.push(env.DB.prepare(`
      INSERT INTO transfer_batch_recipients (
        batch_id, recipient_device_id, status, created_at
      ) VALUES (?, ?, 'pending', ?)
    `).bind(batchId, recipient.id, now));
  }

  try {
    await env.DB.batch(statements);
  } catch (err) {
    console.error("Automatic transfer batch creation failed:", err);
    return error("Transfer batch could not be created.", 409);
  }

  return response({
    batchId,
    recipients: recipients.map(recipient => ({
      deviceId: recipient.id,
      deviceName: recipient.name,
      publicKey: recipient.public_key,
      keyAlgorithm: recipient.key_algorithm
    })),
    status: "pending",
    createdAt: now,
    expiresAt
  }, 201);
}

async function handleUpload(request, env, sender) {
  const declaredLength = Number(request.headers.get("Content-Length") || 0);

  if (declaredLength > 3_000_000) {
    return error("Request is too large.", 413);
  }

  let body;

  try {
    body = await request.json();
  } catch {
    return error("Invalid JSON body.");
  }

  const {
    batchId,
    transferId,
    recipientDeviceId,
    payloadBase64,
    payloadSha256,
    nonce,
    ephemeralPublicKey,
    wrappedKey,
    keyNonce
  } = body || {};

  if (!isUuid(batchId) || !isUuid(transferId) || !isUuid(recipientDeviceId)) {
    return error("Invalid batch, transfer, or recipient ID.");
  }

  if (recipientDeviceId === sender.id) {
    return error("A device cannot send a transfer to itself.");
  }

  if (
    typeof payloadSha256 !== "string" ||
    !/^[a-f0-9]{64}$/i.test(payloadSha256)
  ) {
    return error("Invalid payload checksum.");
  }

  let ciphertext;

  try {
    ciphertext = decodeBase64(payloadBase64);
  } catch {
    return error("Invalid encrypted payload.");
  }

  if (ciphertext.byteLength < 1 || ciphertext.byteLength > MAX_CIPHERTEXT_BYTES) {
    return error("Encrypted payload must be between 1 byte and 2 MB.", 413);
  }

  // AES-GCM nonce: 12 bytes.
  // P-256 uncompressed public key: 65 bytes.
  // AES-256-GCM wrapped key: 48 bytes, including authentication tag.
  try {
    if (
      decodeBase64(nonce).byteLength !== 12 ||
      decodeBase64(ephemeralPublicKey).byteLength !== 65 ||
      decodeBase64(wrappedKey).byteLength !== 48 ||
      decodeBase64(keyNonce).byteLength !== 12
    ) {
      return error("Invalid encryption metadata.");
    }
  } catch {
    return error("Invalid encryption metadata.");
  }

  const actualHash = await sha256Hex(ciphertext);

  if (actualHash.toLowerCase() !== payloadSha256.toLowerCase()) {
    return error("Payload checksum verification failed.");
  }


  const paired = await areDevicesPaired(
    env,
    sender.id,
    recipientDeviceId
  );

  if (!paired) {
    return error("These devices are not paired or the pairing has been revoked.", 403);
  }

  const recipient = await env.DB.prepare(`
    SELECT id FROM devices
    WHERE id = ? AND revoked_at IS NULL
    LIMIT 1
  `).bind(recipientDeviceId).first();

  if (!recipient) {
    return error("Recipient device was not found or has been revoked.", 404);
  }

  const now = Date.now();

  const batch = await env.DB.prepare(`
    SELECT id, expires_at
    FROM transfer_batches
    WHERE id = ?
      AND sender_device_id = ?
      AND status = 'pending'
      AND expires_at > ?
    LIMIT 1
  `).bind(batchId, sender.id, now).first();

  if (!batch) {
    return error("Active transfer batch not found or expired.", 404);
  }

  const rosterEntry = await env.DB.prepare(`
    SELECT status, transfer_id
    FROM transfer_batch_recipients
    WHERE batch_id = ?
      AND recipient_device_id = ?
    LIMIT 1
  `).bind(batchId, recipientDeviceId).first();

  if (!rosterEntry || rosterEntry.status !== "pending" || rosterEntry.transfer_id) {
    return error("Recipient is not available for this batch or already has an upload.", 409);
  }

  const expiresAt = batch.expires_at;
  const objectKey = `${recipientDeviceId}/${crypto.randomUUID()}`;

  await env.PAYLOADS.put(objectKey, ciphertext, {
    httpMetadata: {
      contentType: "application/octet-stream",
      cacheControl: "no-store"
    }
  });

  try {
    const results = await env.DB.batch([
      env.DB.prepare(`
        INSERT INTO transfers (
          id, sender_device_id, recipient_device_id,
          object_key, payload_sha256, ciphertext_size,
          nonce, ephemeral_public_key, wrapped_key, key_nonce,
          created_at, expires_at, status, batch_id
        )
        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?
        WHERE EXISTS (
          SELECT 1
          FROM transfer_batch_recipients
          WHERE batch_id = ?
            AND recipient_device_id = ?
            AND status = 'pending'
            AND transfer_id IS NULL
        )
      `).bind(
        transferId,
        sender.id,
        recipientDeviceId,
        objectKey,
        actualHash,
        ciphertext.byteLength,
        nonce,
        ephemeralPublicKey,
        wrappedKey,
        keyNonce,
        now,
        expiresAt,
        batchId,
        batchId,
        recipientDeviceId
      ),
      env.DB.prepare(`
        UPDATE transfer_batch_recipients
        SET transfer_id = ?
        WHERE batch_id = ?
          AND recipient_device_id = ?
          AND status = 'pending'
          AND transfer_id IS NULL
      `).bind(transferId, batchId, recipientDeviceId)
    ]);

    if (
      (results[0]?.meta?.changes || 0) !== 1 ||
      (results[1]?.meta?.changes || 0) !== 1
    ) {
      await env.DB.prepare(`
        DELETE FROM transfers
        WHERE id = ? AND recipient_device_id = ? AND batch_id = ?
      `).bind(transferId, recipientDeviceId, batchId).run();

      await env.PAYLOADS.delete(objectKey);
      return error("Recipient slot was already used or is no longer available.", 409);
    }
  } catch (err) {
    console.error("Transfer registration failed:", err);
    await env.PAYLOADS.delete(objectKey);
    return error("Transfer could not be registered. Check for a duplicate transfer ID.", 409);
  }

  return response({
    batchId,
    transferId,
    recipientDeviceId,
    status: "pending",
    expiresAt
  }, 201);
}

async function handleList(request, env, device) {
  const result = await env.DB.prepare(`
    SELECT id, sender_device_id, created_at, expires_at, ciphertext_size
    FROM transfers
    WHERE recipient_device_id = ?
      AND status = 'pending'
      AND expires_at > ?
    ORDER BY created_at ASC
    LIMIT 100
  `).bind(device.id, Date.now()).all();

  return response({ transfers: result.results || [] });
}

async function handleFetch(env, device, transferId) {
  if (!isUuid(transferId)) return error("Invalid transfer ID.");

  const transfer = await env.DB.prepare(`
    SELECT *
    FROM transfers
    WHERE id = ?
      AND recipient_device_id = ?
      AND status = 'pending'
      AND expires_at > ?
    LIMIT 1
  `).bind(transferId, device.id, Date.now()).first();

  if (!transfer) return error("Pending transfer not found.", 404);

  const object = await env.PAYLOADS.get(transfer.object_key);

  if (!object) {
    return error("Encrypted payload is temporarily unavailable.", 503);
  }

  const ciphertext = new Uint8Array(await object.arrayBuffer());

  if (ciphertext.byteLength !== transfer.ciphertext_size) {
    return error("Stored payload size verification failed.", 500);
  }

  const actualHash = await sha256Hex(ciphertext);

  if (actualHash !== transfer.payload_sha256) {
    return error("Stored payload integrity verification failed.", 500);
  }

  return response({
    transferId: transfer.id,
    senderDeviceId: transfer.sender_device_id,
    createdAt: transfer.created_at,
    expiresAt: transfer.expires_at,
    payloadSha256: transfer.payload_sha256,
    payloadBase64: encodeBase64(ciphertext),
    nonce: transfer.nonce,
    ephemeralPublicKey: transfer.ephemeral_public_key,
    wrappedKey: transfer.wrapped_key,
    keyNonce: transfer.key_nonce
  });
}

async function handleAcknowledge(env, device, transferId) {
  if (!isUuid(transferId)) return error("Invalid transfer ID.");

  const now = Date.now();

  const transfer = await env.DB.prepare(`
    SELECT object_key, batch_id
    FROM transfers
    WHERE id = ?
      AND recipient_device_id = ?
      AND status = 'pending'
      AND expires_at > ?
    LIMIT 1
  `).bind(transferId, device.id, now).first();

  if (!transfer) {
    const existing = await env.DB.prepare(`
      SELECT t.status, r.status AS recipient_status
      FROM transfers t
      LEFT JOIN transfer_batch_recipients r
        ON r.batch_id = t.batch_id
       AND r.recipient_device_id = t.recipient_device_id
      WHERE t.id = ?
        AND t.recipient_device_id = ?
      LIMIT 1
    `).bind(transferId, device.id).first();

    if (
      existing?.status === "delivered" &&
      existing?.recipient_status === "delivered"
    ) {
      return response({ transferId, status: "delivered" });
    }

    return error("Pending transfer not found or expired.", 404);
  }

  if (!transfer.batch_id) {
    return error("Transfer is not linked to a valid batch.", 409);
  }

  try {
    await env.DB.batch([
      env.DB.prepare(`
        UPDATE transfers
        SET status = 'delivered',
            acknowledged_at = ?
        WHERE id = ?
          AND recipient_device_id = ?
          AND batch_id = ?
          AND status = 'pending'
          AND expires_at > ?
      `).bind(now, transferId, device.id, transfer.batch_id, now),

      env.DB.prepare(`
        UPDATE transfer_batch_recipients
        SET status = 'delivered',
            acknowledged_at = ?
        WHERE batch_id = ?
          AND recipient_device_id = ?
          AND transfer_id = ?
          AND status = 'pending'
          AND EXISTS (
            SELECT 1
            FROM transfers
            WHERE id = ?
              AND recipient_device_id = ?
              AND batch_id = ?
              AND status = 'delivered'
          )
      `).bind(
        now,
        transfer.batch_id,
        device.id,
        transferId,
        transferId,
        device.id,
        transfer.batch_id
      ),

      env.DB.prepare(`
        UPDATE transfer_batches
        SET status = 'completed',
            completed_at = ?,
            cleanup_after = ?
        WHERE id = ?
          AND status = 'pending'
          AND (
            SELECT COUNT(*)
            FROM transfer_batch_recipients
            WHERE batch_id = ?
          ) = expected_recipients
          AND NOT EXISTS (
            SELECT 1
            FROM transfer_batch_recipients
            WHERE batch_id = ?
              AND status != 'delivered'
          )
      `).bind(
        now,
        now + BATCH_METADATA_GRACE_MS,
        transfer.batch_id,
        transfer.batch_id,
        transfer.batch_id
      )
    ]);
  } catch (err) {
    console.error("Acknowledgement transaction failed:", err);
    return error("Acknowledgement could not be recorded. Please retry.", 503);
  }

  const confirmation = await env.DB.prepare(`
    SELECT t.status, r.status AS recipient_status,
           b.status AS batch_status
    FROM transfers t
    JOIN transfer_batch_recipients r
      ON r.batch_id = t.batch_id
     AND r.recipient_device_id = t.recipient_device_id
    JOIN transfer_batches b
      ON b.id = t.batch_id
    WHERE t.id = ?
      AND t.recipient_device_id = ?
    LIMIT 1
  `).bind(transferId, device.id).first();

  if (
    confirmation?.status !== "delivered" ||
    confirmation?.recipient_status !== "delivered"
  ) {
    return error("Transfer could not be acknowledged. It may have expired.", 409);
  }

  let cleanupPending = false;

  try {
    await env.PAYLOADS.delete(transfer.object_key);
  } catch (err) {
    cleanupPending = true;
    console.error("Acknowledged payload deletion will be retried:", transfer.object_key, err);
  }

  return response({
    transferId,
    status: "delivered",
    batchStatus: confirmation.batch_status,
    cleanupPending
  });
}

const BATCH_METADATA_GRACE_MS = 24 * 60 * 60 * 1000;

async function cleanupExpiredTransfers(env) {
  const now = Date.now();
  const cleanupAfter = now + BATCH_METADATA_GRACE_MS;

  let deletedObjects = 0;
  let failedDeletions = 0;
  let expiredTransfers = 0;
  let removedBatches = 0;
  let removedUnbatchedTransfers = 0;

  // Delete acknowledged payloads and retry expired payload deletions.
  const candidates = await env.DB.prepare(`
    SELECT id, recipient_device_id, object_key, status, batch_id
    FROM transfers
    WHERE status = 'delivered'
       OR (
         status IN ('pending', 'expired')
         AND expires_at <= ?
       )
    ORDER BY expires_at ASC
    LIMIT 200
  `).bind(now).all();

  for (const transfer of candidates.results || []) {
    try {
      await env.PAYLOADS.delete(transfer.object_key);
      deletedObjects++;

      if (transfer.status === "pending") {
        if (transfer.batch_id) {
          await env.DB.batch([
            env.DB.prepare(`
              UPDATE transfers
              SET status = 'expired'
              WHERE id = ?
                AND recipient_device_id = ?
                AND batch_id = ?
                AND status = 'pending'
                AND expires_at <= ?
            `).bind(
              transfer.id,
              transfer.recipient_device_id,
              transfer.batch_id,
              now
            ),

            env.DB.prepare(`
              UPDATE transfer_batch_recipients
              SET status = 'expired'
              WHERE batch_id = ?
                AND recipient_device_id = ?
                AND transfer_id = ?
                AND status = 'pending'
            `).bind(
              transfer.batch_id,
              transfer.recipient_device_id,
              transfer.id
            )
          ]);
        } else {
          await env.DB.prepare(`
            UPDATE transfers
            SET status = 'expired'
            WHERE id = ?
              AND recipient_device_id = ?
              AND status = 'pending'
              AND expires_at <= ?
          `).bind(
            transfer.id,
            transfer.recipient_device_id,
            now
          ).run();
        }

        expiredTransfers++;
      }

      // Old standalone records have no batch metadata to preserve.
      if (!transfer.batch_id) {
        await env.DB.prepare(`
          DELETE FROM transfers
          WHERE id = ?
            AND recipient_device_id = ?
            AND status IN ('delivered', 'expired')
        `).bind(
          transfer.id,
          transfer.recipient_device_id
        ).run();

        removedUnbatchedTransfers++;
      }
    } catch (err) {
      failedDeletions++;
      console.error("Payload cleanup failed:", transfer.object_key, err);
    }
  }

  // Complete batches only when the full registered recipient roster
  // has acknowledged successfully.
  await env.DB.prepare(`
    UPDATE transfer_batches
    SET status = 'completed',
        completed_at = ?,
        cleanup_after = ?
    WHERE status = 'pending'
      AND (
        SELECT COUNT(*)
        FROM transfer_batch_recipients
        WHERE batch_id = transfer_batches.id
      ) = expected_recipients
      AND NOT EXISTS (
        SELECT 1
        FROM transfer_batch_recipients
        WHERE batch_id = transfer_batches.id
          AND status != 'delivered'
      )
  `).bind(now, cleanupAfter).run();

  // Expire remaining recipients and batches at the shared 48-hour deadline.
  const expiredBatches = await env.DB.prepare(`
    SELECT id
    FROM transfer_batches
    WHERE status = 'pending'
      AND expires_at <= ?
    LIMIT 200
  `).bind(now).all();

  for (const batch of expiredBatches.results || []) {
    await env.DB.batch([
      env.DB.prepare(`
        UPDATE transfer_batch_recipients
        SET status = 'expired'
        WHERE batch_id = ?
          AND status = 'pending'
      `).bind(batch.id),

      env.DB.prepare(`
        UPDATE transfer_batches
        SET status = 'expired',
            cleanup_after = ?
        WHERE id = ?
          AND status = 'pending'
          AND expires_at <= ?
      `).bind(cleanupAfter, batch.id, now)
    ]);
  }

  // Remove expired and consumed registration challenges.
  await env.DB.prepare(`
    DELETE FROM device_registration_challenges
    WHERE expires_at <= ?
       OR consumed_at IS NOT NULL
  `).bind(now).run();

  // Remove pairing invitations according to their actual lifecycle.
  // A claimed invitation gets its own confirmation deadline.
  await env.DB.prepare(`
    DELETE FROM pairing_sessions
    WHERE (
      claimed_at IS NULL
      AND expires_at <= ?
    )
    OR (
      claimed_at IS NOT NULL
      AND confirmed_at IS NULL
      AND (
        confirmation_expires_at IS NULL
        OR confirmation_expires_at <= ?
      )
    )
    OR (
      confirmed_at IS NOT NULL
      AND confirmed_at <= ?
    )
  `).bind(
    now,
    now,
    now - BATCH_METADATA_GRACE_MS
  ).run();

  // Remove completed/expired batches after the metadata grace period.
  const oldBatches = await env.DB.prepare(`
    SELECT id
    FROM transfer_batches
    WHERE status IN ('completed', 'expired')
      AND cleanup_after IS NOT NULL
      AND cleanup_after <= ?
    ORDER BY cleanup_after ASC
    LIMIT 100
  `).bind(now).all();

  for (const batch of oldBatches.results || []) {
    const deliveries = await env.DB.prepare(`
      SELECT object_key
      FROM transfers
      WHERE batch_id = ?
    `).bind(batch.id).all();

    let batchSafeToDelete = true;

    for (const delivery of deliveries.results || []) {
      try {
        await env.PAYLOADS.delete(delivery.object_key);
        deletedObjects++;
      } catch (err) {
        batchSafeToDelete = false;
        failedDeletions++;
        console.error("Batch payload cleanup failed:", delivery.object_key, err);
        break;
      }
    }

    if (!batchSafeToDelete) continue;

    try {
      await env.DB.batch([
        env.DB.prepare(`
          DELETE FROM transfers
          WHERE batch_id = ?
        `).bind(batch.id),

        env.DB.prepare(`
          DELETE FROM transfer_batches
          WHERE id = ?
            AND status IN ('completed', 'expired')
            AND cleanup_after <= ?
        `).bind(batch.id, now)
      ]);

      removedBatches++;
    } catch (err) {
      console.error("Batch metadata cleanup failed:", batch.id, err);
    }
  }

  console.log("Device Sync cleanup:", JSON.stringify({
    deletedObjects,
    failedDeletions,
    expiredTransfers,
    removedBatches,
    removedUnbatchedTransfers
  }));
}

export default {
  async scheduled(controller, env, ctx) {
    ctx.waitUntil(cleanupExpiredTransfers(env));
  },

  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "GET" && path === "/health") {
      return response({ service: "dch-device-sync", status: "ok" });
    }

    if (!path.startsWith("/v1/")) {
      return error("Route not found.", 404);
    }

    // Registration is intentionally unauthenticated. All subsequent
    // device operations still require a registered device token.
    if (
      request.method === "POST" &&
      path === "/v1/registration/challenge"
    ) {
      return handleRegistrationChallenge(env);
    }

    if (
      request.method === "POST" &&
      path === "/v1/registration"
    ) {
      return handleDeviceRegistration(request, env);
    }

    const device = await authenticate(request, env);

    if (!device) {
      return error("Device authentication required.", 401);
    }

    if (request.method === "POST" && path === "/v1/pairings") {
      return handleCreatePairingInvitation(request, env, device);
    }

    const pairingStatusMatch = path.match(
      /^\/v1\/pairings\/([^/]+)$/
    );

    if (pairingStatusMatch && request.method === "GET") {
      return handleGetPairing(env, device, pairingStatusMatch[1]);
    }

    const pairingConfirmMatch = path.match(
      /^\/v1\/pairings\/([^/]+)\/confirm$/
    );

    if (pairingConfirmMatch && request.method === "POST") {
      return handleConfirmPairing(
        request,
        env,
        device,
        pairingConfirmMatch[1]
      );
    }

    const pairingClaimMatch = path.match(
      /^\/v1\/pairings\/([^/]+)\/claim$/
    );

    if (pairingClaimMatch && request.method === "POST") {
      return handleClaimPairing(
        request,
        env,
        device,
        pairingClaimMatch[1]
      );
    }

    if (request.method === "POST" && path === "/v1/transfer-batches") {
      return handleCreateBatch(request, env, device);
    }

    if (request.method === "POST" && path === "/v1/transfers") {
      return handleUpload(request, env, device);
    }

    if (request.method === "GET" && path === "/v1/transfers") {
      return handleList(request, env, device);
    }

    const match = path.match(/^\/v1\/transfers\/([0-9a-f-]+)(\/ack)?$/i);

    if (match && request.method === "GET" && !match[2]) {
      return handleFetch(env, device, match[1]);
    }

    if (match && request.method === "POST" && match[2] === "/ack") {
      return handleAcknowledge(env, device, match[1]);
    }

    return error("Route not found.", 404);
  }
};
