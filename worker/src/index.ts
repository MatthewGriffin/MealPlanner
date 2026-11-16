import { ALLOWED_MODELS, microDollars } from "./pricing";
import { Tier } from "./quota";
import { hashOf, makeToken, verifyToken } from "./token";
import { Env } from "./objects";
import { entitlementFrom, fetchSubscription } from "./play";
import { decodeToken, judgeVerdict, makeNonce, nonceIsFresh } from "./integrity";

export { UserQuota, Counters, PurchaseOwner } from "./objects";

const MAX_BODY_BYTES = 12 * 1024 * 1024; // photos of packets and recipes arrive as base64
const MAX_OUTPUT_TOKENS = 16_000;
const TIERS: Tier[] = ["free", "pro", "max"];

const today = () => new Date().toISOString().slice(0, 10);
const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
const anthropicError = (status: number, type: string, message: string, headers: Record<string, string> = {}) =>
  json(status, { type: "error", error: { type, message } }, headers);

function usageHeaders(s: { tier: string; percentLeft: number; resetsOn: string | null }): Record<string, string> {
  return {
    "x-pp-tier": s.tier,
    "x-pp-percent-left": String(s.percentLeft),
    "x-pp-resets-on": s.resetsOn ?? "",
  };
}

function bearer(request: Request): string | null {
  const h = request.headers.get("authorization");
  return h?.startsWith("Bearer ") ? h.slice(7) : null;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (request.method === "POST" && url.pathname === "/v1/register/challenge") return json(200, { nonce: await makeNonce(env.TOKEN_SECRET) });
      if (request.method === "POST" && url.pathname === "/v1/register") return await register(request, env);
      if (request.method === "GET" && url.pathname === "/v1/usage") return await usage(request, env);
      if (request.method === "POST" && url.pathname === "/v1/purchase") return await purchase(request, env);
      if (request.method === "POST" && url.pathname === "/v1/messages") return await messages(request, env);
      if (request.method === "POST" && url.pathname === "/admin/tier") return await adminTier(request, env);
      return json(404, { error: "not found" });
    } catch (e) {
      return anthropicError(500, "api_error", "The service had a problem. Please try again.");
    }
  },
};

/** Gives an install its own account. Limited per IP address so accounts cannot be farmed for free allowances. */
async function register(request: Request, env: Env): Promise<Response> {
  const mode = env.INTEGRITY_MODE ?? "off";
  if (mode !== "off") {
    const verdict = await integrityVerdict(request, env);
    // "log" only reports, so installs that cannot prove themselves still work while the check is being proven out.
    console.log(`integrity ${mode}: ${verdict.ok ? "pass" : "fail"}${verdict.reasons.length ? " (" + verdict.reasons.join(", ") + ")" : ""}`);
    if (!verdict.ok && mode === "enforce") {
      return json(403, { error: "This device or app version could not be verified. Install Pantry Pal from Google Play.", code: "integrity" });
    }
  }
  // Only a keyed hash of the address is kept, so no raw IP address is stored anywhere.
  const ip = await hashOf(env.TOKEN_SECRET, request.headers.get("cf-connecting-ip") ?? "unknown");
  const gate = env.COUNTERS.get(env.COUNTERS.idFromName(`reg:${ip}:${today()}`));
  const made = await gate.add(1);
  if (made > Number(env.REGISTRATIONS_PER_IP_PER_DAY)) {
    return json(429, { error: "Too many new accounts from this network today. Try again tomorrow." });
  }
  const userId = crypto.randomUUID();
  return json(200, { token: await makeToken(env.TOKEN_SECRET, userId) });
}

async function integrityVerdict(request: Request, env: Env) {
  let body: { nonce?: string; integrityToken?: string } = {};
  try {
    body = JSON.parse((await request.text()) || "{}");
  } catch {
    return { ok: false, reasons: ["unreadable body"] };
  }
  if (!body.nonce || !body.integrityToken) return { ok: false, reasons: ["no token sent"] };
  if (!(await nonceIsFresh(env.TOKEN_SECRET, body.nonce))) return { ok: false, reasons: ["challenge invalid or expired"] };
  if (!env.GOOGLE_SA_JSON) return { ok: false, reasons: ["server has no Google key"] };
  const packageName = env.PACKAGE_NAME ?? "app.pantrypal.mealplanner";
  try {
    return judgeVerdict(await decodeToken(env.GOOGLE_SA_JSON, packageName, body.integrityToken), { nonce: body.nonce, packageName });
  } catch (e) {
    return { ok: false, reasons: ["could not ask Google: " + (e as Error).message] };
  }
}

async function userFor(request: Request, env: Env) {
  // The Anthropic SDK sends its "API key" in x-api-key; the app puts the account token there.
  const token = request.headers.get("x-api-key") ?? bearer(request);
  const userId = await verifyToken(env.TOKEN_SECRET, token);
  if (!userId) return null;
  return env.USER_QUOTA.get(env.USER_QUOTA.idFromName(userId));
}

async function usage(request: Request, env: Env): Promise<Response> {
  const user = await userFor(request, env);
  if (!user) return json(401, { error: "unknown account" });
  const snap = await user.view(today());
  return json(200, snap, usageHeaders(snap));
}

async function messages(request: Request, env: Env): Promise<Response> {
  const user = await userFor(request, env);
  if (!user) return anthropicError(401, "authentication_error", "This install is not registered.");

  const raw = await request.text();
  if (raw.length > MAX_BODY_BYTES) return anthropicError(413, "request_too_large", "That request is too large.");
  let body: Record<string, unknown>;
  try {
    body = JSON.parse(raw);
  } catch {
    return anthropicError(400, "invalid_request_error", "Invalid JSON.");
  }
  const model = String(body.model ?? "");
  if (!ALLOWED_MODELS.includes(model)) return anthropicError(400, "invalid_request_error", "That model is not available.");
  if (body.stream === true) return anthropicError(400, "invalid_request_error", "Streaming is not supported.");
  if (typeof body.max_tokens === "number" && body.max_tokens > MAX_OUTPUT_TOKENS) body.max_tokens = MAX_OUTPUT_TOKENS;

  // Whole-service backstop, so a flood of fake accounts cannot run up the bill.
  const globalCounter = env.COUNTERS.get(env.COUNTERS.idFromName(`global:${today()}`));
  if ((await globalCounter.get()) >= Number(env.GLOBAL_DAILY_LIMIT_MICROS)) {
    return anthropicError(503, "overloaded_error", "The AI is very busy today. Please try again tomorrow.");
  }

  const check = await user.check(today());
  if (!check.allowed) {
    // 402, not 429: the SDK retries 429s, and waiting would not help here.
    const message =
      check.snapshot.tier === "free"
        ? "You have used this week's free AI allowance."
        : "You have used today's AI allowance.";
    return anthropicError(402, "usage_limit", message, usageHeaders(check.snapshot));
  }

  const upstreamHeaders = new Headers({
    "content-type": "application/json",
    "x-api-key": env.ANTHROPIC_API_KEY,
    "anthropic-version": request.headers.get("anthropic-version") ?? "2023-06-01",
  });
  const beta = request.headers.get("anthropic-beta");
  if (beta) upstreamHeaders.set("anthropic-beta", beta);

  const upstream = await fetch(`${env.UPSTREAM_URL}/v1/messages`, { method: "POST", headers: upstreamHeaders, body: JSON.stringify(body) });
  const text = await upstream.text();

  let snap = check.snapshot;
  if (upstream.ok) {
    let micros = 0;
    try {
      micros = microDollars(model, JSON.parse(text).usage);
    } catch {
      // An unreadable reply is not charged.
    }
    if (micros > 0) {
      snap = await user.charge(today(), micros);
      await globalCounter.add(micros);
    }
  }
  return new Response(text, {
    status: upstream.status,
    headers: { "content-type": upstream.headers.get("content-type") ?? "application/json", ...usageHeaders(snap) },
  });
}

/** Sets an account's plan. Used for testing now and for the billing webhook later. */
/**
 * The app reports a Google Play purchase; the Worker asks Google whether it is good and which plan it is, then sets the
 * plan with an end date. A purchase belongs to one account at a time: claiming it moves it, so it cannot be shared.
 */
async function purchase(request: Request, env: Env): Promise<Response> {
  const user = await userFor(request, env);
  if (!user) return json(401, { error: "unknown account" });
  const userId = await verifyToken(env.TOKEN_SECRET, request.headers.get("x-api-key") ?? bearer(request));
  const body = (await request.json().catch(() => ({}))) as { purchaseToken?: string };
  const token = body.purchaseToken;
  if (!token || token.length > 2000 || !userId) return json(400, { error: "need a purchaseToken" });
  if (!env.GOOGLE_SA_JSON) return json(503, { error: "Subscriptions are not available yet." });

  let payload: unknown;
  try {
    payload = await fetchSubscription(env.GOOGLE_SA_JSON, env.PACKAGE_NAME ?? "app.pantrypal.mealplanner", token);
  } catch (e) {
    return json(502, { error: "Could not check the purchase with Google. Please try again." });
  }
  const ent = entitlementFrom(payload, Date.now());
  if (!ent) return json(200, { entitled: false, ...(await user.view(today())) });

  const owner = env.PURCHASE_OWNER.get(env.PURCHASE_OWNER.idFromName(await hashOf(env.TOKEN_SECRET, "purchase:" + token)));
  const previous = await owner.claim(userId);
  if (previous) await env.USER_QUOTA.get(env.USER_QUOTA.idFromName(previous)).setPlan(today(), "free");
  const snap = await user.setPaid(today(), ent.tier, ent.paidUntil, token);
  return json(200, { entitled: true, ...snap }, usageHeaders(snap));
}

async function adminTier(request: Request, env: Env): Promise<Response> {
  if (!env.ADMIN_SECRET || bearer(request) !== env.ADMIN_SECRET) return json(401, { error: "no" });
  const { token, tier } = (await request.json()) as { token?: string; tier?: string };
  const userId = await verifyToken(env.TOKEN_SECRET, token);
  if (!userId || !TIERS.includes(tier as Tier)) return json(400, { error: "need a valid token and tier (free, pro, max)" });
  const user = env.USER_QUOTA.get(env.USER_QUOTA.idFromName(userId));
  return json(200, await user.setPlan(today(), tier as Tier));
}
