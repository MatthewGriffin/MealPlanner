// A token is "<userId>.<signature>". Only this Worker can make one, so a client cannot invent accounts or pick someone else's.
const enc = new TextEncoder();

async function sign(secret: string, data: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, enc.encode(data));
  return btoa(String.fromCharCode(...new Uint8Array(sig))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** A keyed one-way hash, for counters that must not hold personal data such as an IP address. */
export async function hashOf(secret: string, value: string): Promise<string> {
  return (await sign(secret, "hash:" + value)).slice(0, 22);
}

export async function makeToken(secret: string, userId: string): Promise<string> {
  return `${userId}.${await sign(secret, userId)}`;
}

/** Returns the userId if the token is genuine, else null. */
export async function verifyToken(secret: string, token: string | null | undefined): Promise<string | null> {
  if (!token) return null;
  const dot = token.lastIndexOf(".");
  if (dot <= 0) return null;
  const userId = token.slice(0, dot);
  const given = token.slice(dot + 1);
  const expected = await sign(secret, userId);
  if (given.length !== expected.length) return null;
  let diff = 0;
  for (let i = 0; i < given.length; i++) diff |= given.charCodeAt(i) ^ expected.charCodeAt(i);
  return diff === 0 ? userId : null;
}
