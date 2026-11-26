import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import worker from "../src/index.js";

const senderId = "11111111-1111-4111-8111-111111111111";
const recipientId = "22222222-2222-4222-8222-222222222222";
const otherId = "33333333-3333-4333-8333-333333333333";
const batchId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const senderToken = "A".repeat(43);
const otherToken = "B".repeat(43);

const hash = value =>
  createHash("sha256").update(value).digest("hex");

class BatchMockD1 {
  constructor() {
    this.devices = [
      {
        id: senderId,
        name: "Sender",
        token_hash: hash(senderToken),
        public_key: "sender-key",
        key_algorithm: "P-256",
        revoked_at: null
      },
      {
        id: recipientId,
        name: "Recipient",
        token_hash: null,
        public_key: "recipient-key",
        key_algorithm: "P-256",
        revoked_at: null
      },
      {
        id: otherId,
        name: "Other Sender",
        token_hash: hash(otherToken),
        public_key: "other-key",
        key_algorithm: "P-256",
        revoked_at: null
      }
    ];

    this.pairs = [{
      id: "pair-1",
      device_a_id: senderId,
      device_b_id: recipientId,
      revoked_at: null
    }];

    this.batches = new Map();
    this.roster = [];
  }

  prepare(sql) {
    const db = this;
    let params = [];

    return {
      sql,
      get params() {
        return params;
      },

      bind(...values) {
        params = values;
        return this;
      },

      async first() {
        if (sql.includes("FROM devices") && sql.includes("token_hash")) {
          return db.devices.find(
            d => d.token_hash === params[0] && d.revoked_at === null
          ) || null;
        }

        if (sql.includes("FROM transfer_batches")) {
          return db.batches.get(params[0]) || null;
        }

        return null;
      },

      async all() {
        if (sql.includes("FROM device_pairs p") &&
            sql.includes("JOIN devices d")) {
          const sender = params[0];

          const results = db.pairs
            .filter(p =>
              (p.device_a_id === sender || p.device_b_id === sender) &&
              p.revoked_at === null
            )
            .map(p => {
              const id = p.device_a_id === sender
                ? p.device_b_id
                : p.device_a_id;
              return db.devices.find(
                d => d.id === id && d.revoked_at === null
              );
            })
            .filter(Boolean)
            .map(d => ({
              id: d.id,
              name: d.name,
              public_key: d.public_key,
              key_algorithm: d.key_algorithm
            }));

          return { results };
        }

        if (sql.includes("FROM transfer_batch_recipients")) {
          const [sender, , requestedBatchId] = params;

          const results = db.roster
            .filter(r => r.batch_id === requestedBatchId)
            .map(r => {
              const device = db.devices.find(
                d => d.id === r.recipient_device_id
              );
              const pair = db.pairs.find(p =>
                (
                  (p.device_a_id === sender &&
                   p.device_b_id === r.recipient_device_id) ||
                  (p.device_b_id === sender &&
                   p.device_a_id === r.recipient_device_id)
                )
              );

              return {
                recipient_device_id: r.recipient_device_id,
                status: r.status,
                transfer_id: r.transfer_id ?? null,
                name: device?.name,
                public_key: device?.public_key,
                key_algorithm: device?.key_algorithm,
                revoked_at: device?.revoked_at ?? null,
                pair_active: pair && pair.revoked_at === null ? 1 : 0
              };
            });

          return { results };
        }

        return { results: [] };
      },

      async run() {
        if (sql.includes("UPDATE devices SET last_seen_at")) {
          const device = db.devices.find(d => d.id === params[1]);
          if (device) device.last_seen_at = params[0];
        }
        return { meta: { changes: 1 } };
      }
    };
  }

  async batch(statements) {
    const batches = new Map(this.batches);
    const roster = this.roster.map(r => ({ ...r }));

    try {
      for (const statement of statements) {
        const sql = statement.sql;
        const p = statement.params;

        if (sql.includes("INSERT INTO transfer_batches")) {
          if (this.batches.has(p[0])) {
            throw new Error("Duplicate batch");
          }

          this.batches.set(p[0], {
            id: p[0],
            sender_device_id: p[1],
            expected_recipients: p[2],
            created_at: p[3],
            expires_at: p[4],
            status: "pending",
            completed_at: null
          });
        } else if (sql.includes("INSERT INTO transfer_batch_recipients")) {
          this.roster.push({
            batch_id: p[0],
            recipient_device_id: p[1],
            status: "pending",
            created_at: p[2],
            transfer_id: null
          });
        }
      }
    } catch (err) {
      this.batches = batches;
      this.roster = roster;
      throw err;
    }
  }
}

function setup() {
  const env = { DB: new BatchMockD1() };

  function request(token, id = batchId) {
    return new Request(
      "https://worker.test/v1/transfer-batches",
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({ batchId: id })
      }
    );
  }

  return { env, request };
}

test("creates a batch for every active paired recipient", async () => {
  const { env, request } = setup();

  const response = await worker.fetch(request(senderToken), env);
  const data = await response.json();

  assert.equal(response.status, 201);
  assert.equal(data.batchId, batchId);
  assert.equal(data.status, "pending");
  assert.equal(data.recipients.length, 1);
  assert.equal(data.recipients[0].deviceId, recipientId);
  assert.equal(data.recipients[0].publicKey, "recipient-key");
  assert.equal(data.recipients[0].active, true);
  assert.equal(env.DB.roster.length, 1);
});

test("retrying the same batch ID returns the existing batch", async () => {
  const { env, request } = setup();

  const first = await worker.fetch(request(senderToken), env);
  const firstData = await first.json();

  const second = await worker.fetch(request(senderToken), env);
  const secondData = await second.json();

  assert.equal(first.status, 201);
  assert.equal(second.status, 200);
  assert.equal(secondData.batchId, batchId);
  assert.equal(secondData.createdAt, firstData.createdAt);
  assert.equal(secondData.recipients.length, 1);
  assert.equal(env.DB.batches.size, 1);
  assert.equal(env.DB.roster.length, 1);
});

test("another device cannot reuse a sender's batch ID", async () => {
  const { env, request } = setup();

  await worker.fetch(request(senderToken), env);

  const response = await worker.fetch(request(otherToken), env);
  const data = await response.json();

  assert.equal(response.status, 409);
  assert.match(data.error, /another device/i);
  assert.equal(env.DB.batches.size, 1);
});

test("retry does not expose the key of a recipient with revoked pairing", async () => {
  const { env, request } = setup();

  await worker.fetch(request(senderToken), env);
  env.DB.pairs[0].revoked_at = Date.now();

  const response = await worker.fetch(request(senderToken), env);
  const data = await response.json();

  assert.equal(response.status, 200);
  assert.equal(data.recipients[0].active, false);
  assert.equal(data.recipients[0].publicKey, null);
  assert.equal(data.recipients[0].keyAlgorithm, null);
});
