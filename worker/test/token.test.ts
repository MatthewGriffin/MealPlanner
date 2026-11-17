import { describe, expect, it } from "vitest";
import { cleanDeviceId } from "../src/device";
import { makeFollowerToken, makeToken, verifyToken, verifyTokenParts } from "../src/token";

const SECRET = "test-secret";

describe("tokens", () => {
  it("an account token verifies as the account with no follower key", async () => {
    const t = await makeToken(SECRET, "user-1");
    expect(await verifyTokenParts(SECRET, t)).toEqual({ userId: "user-1", keyId: null });
    expect(await verifyToken(SECRET, t)).toBe("user-1");
  });

  it("a follower token carries the account and its key, and is not an account token", async () => {
    const t = await makeFollowerToken(SECRET, "user-1", "abc123");
    expect(await verifyTokenParts(SECRET, t)).toEqual({ userId: "user-1", keyId: "abc123" });
    expect(await verifyToken(SECRET, t)).toBeNull();
  });

  it("a forged or altered token is refused", async () => {
    const t = await makeFollowerToken(SECRET, "user-1", "abc123");
    expect(await verifyTokenParts(SECRET, t.replace("abc123", "zzz999"))).toBeNull();
    expect(await verifyTokenParts("other-secret", t)).toBeNull();
    expect(await verifyTokenParts(SECRET, "user-1.nope")).toBeNull();
    expect(await verifyTokenParts(SECRET, null)).toBeNull();
  });

  it("a follower cannot turn its token into the account's own", async () => {
    const t = await makeFollowerToken(SECRET, "user-1", "abc123");
    const stripped = "user-1." + t.slice(t.lastIndexOf(".") + 1);
    expect(await verifyTokenParts(SECRET, stripped)).toBeNull();
  });
});

describe("device ids", () => {
  it("accepts an Android ID and rejects odd values", () => {
    expect(cleanDeviceId("9774d56d682e549c")).toBe("9774d56d682e549c");
    expect(cleanDeviceId("short")).toBeNull();
    expect(cleanDeviceId("has spaces in it!")).toBeNull();
    expect(cleanDeviceId(42)).toBeNull();
    expect(cleanDeviceId("x".repeat(65))).toBeNull();
  });
});
