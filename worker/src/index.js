const MAX_BODY_SIZE = 8 * 1024 * 1024;
const MAX_IMAGE_SIZE = 6 * 1024 * 1024;
const MAX_TEXT_LENGTH = 30000;

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
  "Cache-Control": "no-store"
};

function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      ...corsHeaders,
      "Content-Type": "application/json; charset=utf-8",
      ...extraHeaders
    }
  });
}

function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  if (a.length !== b.length) return false;

  let difference = 0;

  for (let i = 0; i < a.length; i++) {
    difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }

  return difference === 0;
}

function validImageData(value, mimeType) {
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    value.length > Math.ceil(MAX_IMAGE_SIZE / 3) * 4 ||
    value.length % 4 !== 0 ||
    !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)
  ) {
    return false;
  }

  let decoded;

  try {
    decoded = atob(value);
  } catch {
    return false;
  }

  if (decoded.length === 0 || decoded.length > MAX_IMAGE_SIZE) {
    return false;
  }

  if (btoa(decoded) !== value) {
    return false;
  }

  if (mimeType === "image/jpeg") {
    return (
      decoded.length >= 3 &&
      decoded.charCodeAt(0) === 0xFF &&
      decoded.charCodeAt(1) === 0xD8 &&
      decoded.charCodeAt(2) === 0xFF
    );
  }

  if (mimeType === "image/png") {
    return (
      decoded.length >= 8 &&
      decoded.charCodeAt(0) === 0x89 &&
      decoded.slice(1, 4) === "PNG" &&
      decoded.charCodeAt(4) === 0x0D &&
      decoded.charCodeAt(5) === 0x0A &&
      decoded.charCodeAt(6) === 0x1A &&
      decoded.charCodeAt(7) === 0x0A
    );
  }

  if (mimeType === "image/webp") {
    return (
      decoded.length >= 12 &&
      decoded.slice(0, 4) === "RIFF" &&
      decoded.slice(8, 12) === "WEBP"
    );
  }

  return false;
}

function extractText(data) {
  const parts = data?.candidates?.[0]?.content?.parts ?? [];

  return parts
    .filter(part => typeof part.text === "string")
    .map(part => part.text)
    .join("\n")
    .trim();
}

async function readBoundedBody(request) {
  if (!request.body) {
    return { error: "empty" };
  }

  const reader = request.body.getReader();
  const chunks = [];
  let totalBytes = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();

      if (done) break;

      totalBytes += value.byteLength;

      if (totalBytes > MAX_BODY_SIZE) {
        await reader.cancel();
        return { error: "too_large" };
      }

      chunks.push(value);
    }
  } catch {
    return { error: "read_failed" };
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(totalBytes);
  let offset = 0;

  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  try {
    return {
      text: new TextDecoder("utf-8", { fatal: true }).decode(bytes)
    };
  } catch {
    return { error: "invalid_encoding" };
  }
}

async function callGemini(env, parts, options = {}) {
  const model = env.GEMINI_MODEL || "gemini-3.5-flash-lite";

  const endpoint =
    "https://generativelanguage.googleapis.com/v1beta/models/" +
    encodeURIComponent(model) +
    ":generateContent";

  let response;

  try {
    response = await fetch(endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": env.GEMINI_API_KEY
      },
      body: JSON.stringify({
        contents: [
          {
            role: "user",
            parts
          }
        ],
        generationConfig: {
          temperature: options.temperature ?? 0.2,
          maxOutputTokens: options.maxOutputTokens ?? 4096
        }
      })
    });
  } catch {
    return {
      error: json(
        { error: "Could not connect to the AI provider. Please retry." },
        503
      )
    };
  }

  const result = await response.json().catch(() => null);

  if (!response.ok) {
    const status = response.status === 429 ? 429 : 502;

    return {
      error: json(
        {
          error: status === 429
            ? "Gemini quota or rate limit reached. Try again later."
            : "The AI provider could not process this request.",
          providerStatus: response.status
        },
        status
      )
    };
  }

  const text = extractText(result);

  if (!text) {
    return {
      error: json(
        { error: "The AI returned an empty response. Please retry." },
        502
      )
    };
  }

  return { text };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    if (request.method === "GET" && path === "/health") {
      return json({
        service: "DCH AI Gateway",
        status: "online",
        model: env.GEMINI_MODEL || "gemini-3.5-flash-lite"
      });
    }

    if (request.method !== "POST") {
      return json({ error: "Method not allowed." }, 405);
    }

    if (path !== "/v1/reconstruct" && path !== "/v1/answer") {
      return json({ error: "Endpoint not found." }, 404);
    }

    const authorization = request.headers.get("Authorization") || "";
    const suppliedToken = authorization.startsWith("Bearer ")
      ? authorization.slice(7)
      : "";

    if (!env.DCH_GATEWAY_TOKEN || !safeEqual(
      suppliedToken,
      env.DCH_GATEWAY_TOKEN
    )) {
      const ip = request.headers.get("CF-Connecting-IP") || "unknown";

      const authLimit = await env.AUTH_RATE_LIMITER.limit({ key: ip });

      if (!authLimit.success) {
        return json(
          { error: "Too many authentication attempts. Try again later." },
          429,
          { "Retry-After": "60" }
        );
      }

      return json({ error: "Unauthorized." }, 401);
    }

    const rateLimit = await env.AI_RATE_LIMITER.limit({
      key: "dch-personal-installation"
    });

    if (!rateLimit.success) {
      return json(
        { error: "Too many AI requests. Please wait one minute." },
        429,
        { "Retry-After": "60" }
      );
    }

    if (!env.GEMINI_API_KEY) {
      return json({ error: "AI provider is not configured." }, 503);
    }

    const contentType = request.headers.get("Content-Type") || "";

    if (!/^application\/json(?:\s*;|$)/i.test(contentType)) {
      return json({ error: "Content-Type must be application/json." }, 415);
    }

    const contentLengthHeader = request.headers.get("Content-Length");

    if (contentLengthHeader !== null) {
      const contentLength = Number(contentLengthHeader);

      if (!Number.isSafeInteger(contentLength) || contentLength < 0) {
        return json({ error: "Invalid Content-Length." }, 400);
      }

      if (contentLength > MAX_BODY_SIZE) {
        return json({ error: "Request is too large." }, 413);
      }
    }

    const bodyResult = await readBoundedBody(request);

    if (bodyResult.error === "too_large") {
      return json({ error: "Request exceeds the 8 MB limit." }, 413);
    }

    if (bodyResult.error === "empty") {
      return json({ error: "Request body is empty." }, 400);
    }

    if (bodyResult.error) {
      return json({ error: "Unable to read a valid request body." }, 400);
    }

    let body;

    try {
      body = JSON.parse(bodyResult.text);
    } catch {
      return json({ error: "Invalid JSON request." }, 400);
    }

    if (!body || typeof body !== "object" || Array.isArray(body)) {
      return json({ error: "JSON body must be an object." }, 400);
    }

    if (path === "/v1/reconstruct") {
      const image = body?.image;

      if (
        !image ||
        !["image/jpeg", "image/png", "image/webp"].includes(image.mimeType) ||
        !validImageData(image.data, image.mimeType)
      ) {
        return json({ error: "A valid JPEG, PNG, or WebP image is required." }, 400);
      }

      const estimatedBytes = Math.floor(image.data.length * 0.75);

      if (estimatedBytes > MAX_IMAGE_SIZE) {
        return json({ error: "Image exceeds the 6 MB limit." }, 413);
      }

      const pageNumber = Number.isInteger(body.pageNumber)
        ? Math.max(1, Math.min(body.pageNumber, 100))
        : 1;

      const totalPages = Number.isInteger(body.totalPages)
        ? Math.max(pageNumber, Math.min(body.totalPages, 100))
        : pageNumber;

      const prompt = `
You are the document reconstruction engine for Devil's Cute Horns AI.

Carefully inspect this photographed document page.

Your task is to reconstruct the visible document faithfully:
- Transcribe every readable word, number, heading, instruction and question.
- Preserve the original order and question numbering.
- Preserve mathematical notation, equations, units and symbols as accurately as possible.
- Describe diagrams, tables, charts and illustrations in concise square-bracketed notes where necessary.
- Preserve paragraph and list structure.
- Do not answer or solve any question.
- Do not invent missing words or silently correct uncertain content.
- Mark genuinely unreadable text as [unclear].
- If a word is uncertain, provide your best reading followed by [uncertain].
- Ignore camera artifacts and do not describe the photograph itself.

This is page ${pageNumber} of ${totalPages}.

Return only the reconstructed document content. Do not add an introduction or a conclusion.
      `.trim();

      const result = await callGemini(
        env,
        [
          { text: prompt },
          {
            inlineData: {
              mimeType: image.mimeType,
              data: image.data
            }
          }
        ],
        { temperature: 0.1, maxOutputTokens: 4096 }
      );

      if (result.error) return result.error;

      return json({
        text: result.text,
        model: env.GEMINI_MODEL || "gemini-3.5-flash-lite",
        pageNumber
      });
    }

    if (path === "/v1/answer") {
      const questionText = body?.questionText;

      if (
        typeof questionText !== "string" ||
        questionText.trim().length === 0 ||
        questionText.length > MAX_TEXT_LENGTH
      ) {
        return json(
          { error: "Question text is required and must not exceed 30,000 characters." },
          400
        );
      }

      const instruction = typeof body.instruction === "string"
        ? body.instruction.slice(0, 2000)
        : "";

      const prompt = `
You are Devil's Cute Horns AI, an intelligent academic learning and document-answering assistant.

First, analyze the COMPLETE reconstructed paper, including instructions that may appear before, between, or after the questions. Treat the paper's printed instructions as binding constraints.

QUESTION SELECTION AND INSTRUCTION RULES:
- Identify the paper's general instructions, section instructions, compulsory questions, optional questions, alternatives, and sub-question requirements before answering.
- If the paper says "Answer any THREE", answer exactly THREE eligible questions, not all of them.
- If it says "Answer ALL", answer all required questions.
- If a section requires a specific number, obey that number independently for that section.
- Never omit compulsory questions.
- When a choice is permitted, select the easiest and quickest questions that can be answered accurately from the available information.
- Consider marks, complexity, required calculations, clarity, and available information when selecting.
- If a question contains alternatives such as "OR", answer only the permitted alternative unless the instructions require both.
- Respect sub-question counts, word limits, marks, and required combinations.
- Do not answer optional questions that were not selected.
- Briefly state which optional questions you selected and why, without replacing the actual answers with selection commentary.
- If instructions conflict or are genuinely ambiguous, explain the ambiguity and make the safest reasonable interpretation. Never silently violate an explicit question count.

ANSWER QUALITY:
- Preserve original question numbering and section structure.
- Show working steps for mathematical and scientific problems.
- Explain concepts clearly and define important terms.
- Use relevant examples where helpful.
- For essays, provide coherent, well-structured responses.
- If information is missing or a diagram is ambiguous, say so.
- Never fabricate facts, citations, formulas, or results.
- Distinguish assumptions from established facts.
- Format the response with readable headings and lists.
- Produce complete, useful answers rather than merely describing how to answer.

Additional instruction:
${instruction || "No additional instruction."}

COMPLETE RECONSTRUCTED PAPER:
${questionText.trim()}
      `.trim();

      const result = await callGemini(
        env,
        [{ text: prompt }],
        { temperature: 0.3, maxOutputTokens: 4096 }
      );

      if (result.error) return result.error;

      return json({
        text: result.text,
        model: env.GEMINI_MODEL || "gemini-3.5-flash-lite"
      });
    }

    return json({ error: "Endpoint not found." }, 404);
  }
};
