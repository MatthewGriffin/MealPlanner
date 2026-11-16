// Play Integrity: proves a sign-up comes from the genuine app, installed from Google Play, on a real device.
// Stateless on purpose: nothing about the check is stored.
import { sign } from "./token";

const NONCE_MAX_AGE_MS = 5 * 60 * 1000;
const enc = new TextEncoder();

const b64url = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_");
const unb64 = (s: string) => atob(s.replace(/-/g, "+").replace(/_/g, "/"));

/** A one-time challenge: "random.time.signature", base64url. The app hands it to Google so the token is bound to it. */
export async function makeNonce(secret: string, now = Date.now()): Promise<string> {
  const rand = b64url(crypto.getRandomValues(new Uint8Array(16))).replace(/=+$/, "").replace(/[.]/g, "");
  const body = `${rand}.${now}`;
  const text = `${body}.${await sign(secret, "nonce:" + body)}`;
  return b64url(enc.encode(text));
}

/** True if the nonce was made by this Worker and is recent. */
export async function nonceIsFresh(secret: string, nonce: string, now = Date.now()): Promise<boolean> {
  let text: string;
  try {
    text = unb64(nonce);
  } catch {
    return false;
  }
  const parts = text.split(".");
  if (parts.length !== 3) return false;
  const [rand, time, sig] = parts;
  const expected = await sign(secret, `nonce:${rand}.${time}`);
  if (sig.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < sig.length; i++) diff |= sig.charCodeAt(i) ^ expected.charCodeAt(i);
  const age = now - Number(time);
  return diff === 0 && Number.isFinite(age) && age >= 0 && age <= NONCE_MAX_AGE_MS;
}

export interface Verdict {
  ok: boolean;
  /** What failed, for the logs. Never includes anything about the person. */
  reasons: string[];
}

/** Judges a decoded Play Integrity payload. Pure, so it can be tested with fixtures. */
export function judgeVerdict(payload: any, expect: { nonce: string; packageName: string }): Verdict {
  const reasons: string[] = [];
  const req = payload?.requestDetails ?? {};
  if (req.nonce !== expect.nonce) reasons.push("nonce mismatch");
  if (req.requestPackageName !== expect.packageName) reasons.push("wrong package");
  if (payload?.appIntegrity?.appRecognitionVerdict !== "PLAY_RECOGNIZED") reasons.push("app not recognised by Play");
  const device: string[] = payload?.deviceIntegrity?.deviceRecognitionVerdict ?? [];
  if (!device.includes("MEETS_DEVICE_INTEGRITY")) reasons.push("device does not meet integrity");
  return { ok: reasons.length === 0, reasons };
}

const cachedAccess = new Map<string, { token: string; expiresAt: number }>();

function pemToDer(pem: string): ArrayBuffer {
  const body = pem.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  const raw = atob(body);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out.buffer;
}

export async function googleAccessToken(saJson: string, scope: string, now = Date.now()): Promise<string> {
  const hit = cachedAccess.get(scope);
  if (hit && hit.expiresAt - 60_000 > now) return hit.token;
  const sa = JSON.parse(saJson) as { client_email: string; private_key: string };
  const iat = Math.floor(now / 1000);
  const claims = {
    iss: sa.client_email,
    scope,
    aud: "https://oauth2.googleapis.com/token",
    iat,
    exp: iat + 3600,
  };
  const part = (o: unknown) => b64url(enc.encode(JSON.stringify(o))).replace(/=+$/, "");
  const unsigned = `${part({ alg: "RS256", typ: "JWT" })}.${part(claims)}`;
  const key = await crypto.subtle.importKey("pkcs8", pemToDer(sa.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(unsigned)));
  const assertion = `${unsigned}.${b64url(sig).replace(/=+$/, "")}`;
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  if (!res.ok) throw new Error(`google token ${res.status}`);
  const data = (await res.json()) as { access_token: string; expires_in: number };
  cachedAccess.set(scope, { token: data.access_token, expiresAt: now + data.expires_in * 1000 });
  return data.access_token;
}

/** Asks Google to decode a token, and returns the payload. */
export async function decodeToken(saJson: string, packageName: string, integrityToken: string): Promise<any> {
  const access = await googleAccessToken(saJson, "https://www.googleapis.com/auth/playintegrity");
  const res = await fetch(`https://playintegrity.googleapis.com/v1/${packageName}:decodeIntegrityToken`, {
    method: "POST",
    headers: { authorization: `Bearer ${access}`, "content-type": "application/json" },
    body: JSON.stringify({ integrity_token: integrityToken }),
  });
  if (!res.ok) throw new Error(`decode ${res.status}`);
  return ((await res.json()) as any).tokenPayloadExternal;
}
