import { describe, expect, it } from "vitest";
import { judgeVerdict, makeNonce, nonceIsFresh } from "../src/integrity";

const SECRET = "test-secret";
const good = (nonce: string) => ({
  requestDetails: { nonce, requestPackageName: "app.pantrypal.mealplanner" },
  appIntegrity: { appRecognitionVerdict: "PLAY_RECOGNIZED" },
  deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY"] },
});
const expectFor = (nonce: string) => ({ nonce, packageName: "app.pantrypal.mealplanner" });

describe("nonce", () => {
  it("is accepted when fresh, rejected when old, forged or from another secret", async () => {
    const now = 1_000_000;
    const n = await makeNonce(SECRET, now);
    expect(await nonceIsFresh(SECRET, n, now + 1000)).toBe(true);
    expect(await nonceIsFresh(SECRET, n, now + 6 * 60 * 1000)).toBe(false);
    expect(await nonceIsFresh("other", n, now + 1000)).toBe(false);
    expect(await nonceIsFresh(SECRET, "not-a-nonce", now)).toBe(false);
    expect(await nonceIsFresh(SECRET, n, now - 1000)).toBe(false);
  });
});

describe("verdict", () => {
  it("passes a genuine Play install on a real device", () => {
    expect(judgeVerdict(good("n"), expectFor("n")).ok).toBe(true);
  });
  it("fails a sideloaded app, an emulator, a wrong nonce and a wrong package", () => {
    const sideload = { ...good("n"), appIntegrity: { appRecognitionVerdict: "UNRECOGNIZED_VERSION" } };
    expect(judgeVerdict(sideload, expectFor("n")).ok).toBe(false);
    const emulator = { ...good("n"), deviceIntegrity: { deviceRecognitionVerdict: [] } };
    expect(judgeVerdict(emulator, expectFor("n")).ok).toBe(false);
    expect(judgeVerdict(good("other"), expectFor("n")).ok).toBe(false);
    const wrongPkg = { ...good("n"), requestDetails: { nonce: "n", requestPackageName: "evil.app" } };
    expect(judgeVerdict(wrongPkg, expectFor("n")).ok).toBe(false);
    expect(judgeVerdict(undefined, expectFor("n")).ok).toBe(false);
  });
});
