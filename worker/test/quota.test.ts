import { describe, expect, it } from "vitest";
import { NEW_STATE, activePeriodStart, carryUsage, canUse, record, setTier, snapshot } from "../src/quota";
import { microDollars } from "../src/pricing";
import { hashOf, makeToken, verifyToken } from "../src/token";

const D = "2026-11-16";

describe("quota", () => {
  it("free week can be burned at once, then waits until the week ends", () => {
    let s = NEW_STATE;
    for (let i = 0; i < 15; i++) {
      expect(canUse(s, D)).toBe(true);
      s = record(s, D, 4_500);
    }
    expect(canUse(s, D)).toBe(true); // 2,500 left: one more may start
    s = record(s, D, 4_500);
    expect(canUse(s, D)).toBe(false);
    expect(canUse(s, "2026-11-22")).toBe(false);
    expect(snapshot(s, "2026-11-22").resetsOn).toBe("2026-11-23");
    expect(canUse(s, "2026-11-23")).toBe(true);
  });

  it("the week starts at first use", () => {
    expect(snapshot(NEW_STATE, D).resetsOn).toBeNull();
    const s = record(NEW_STATE, "2026-11-19", 4_500);
    expect(snapshot(s, "2026-11-19").resetsOn).toBe("2026-11-26");
  });

  it("pro is daily and fits 20 meal requests", () => {
    let s = setTier(NEW_STATE, "pro");
    for (let i = 0; i < 20; i++) {
      expect(canUse(s, D)).toBe(true);
      s = record(s, D, 4_500);
    }
    expect(canUse(s, D)).toBe(false);
    expect(canUse(s, "2026-11-17")).toBe(true);
  });

  it("max is double pro, and changing plan starts fresh", () => {
    let s = record(NEW_STATE, D, 70_000);
    expect(canUse(s, D)).toBe(false);
    s = setTier(s, "max");
    expect(activePeriodStart(s, D)).toBeNull();
    s = record(s, D, 90_000);
    expect(canUse(s, D)).toBe(true);
    s = record(s, D, 90_000);
    expect(canUse(s, D)).toBe(false);
  });

  it("percent left", () => {
    expect(snapshot(NEW_STATE, D).percentLeft).toBe(100);
    expect(snapshot(record(NEW_STATE, D, 35_000), D).percentLeft).toBe(50);
  });
});

describe("pricing", () => {
  it("matches the app", () => {
    expect(microDollars("claude-haiku-5-5", { input_tokens: 10_000, output_tokens: 1_000 })).toBe(1_500);
    expect(microDollars("claude-haiku-5-5", { server_tool_use: { web_search_requests: 1 } })).toBe(10_000);
    expect(microDollars("mystery", { input_tokens: 1_000_000 })).toBe(2_000_000);
    expect(microDollars("claude-haiku-5-5", undefined)).toBe(0);
  });
});

describe("tokens", () => {
  it("only genuine tokens verify", async () => {
    const t = await makeToken("secret", "user-1");
    expect(await verifyToken("secret", t)).toBe("user-1");
    expect(await verifyToken("other", t)).toBeNull();
    expect(await verifyToken("secret", "user-2." + t.split(".")[1])).toBeNull();
    expect(await verifyToken("secret", "garbage")).toBeNull();
    expect(await verifyToken("secret", null)).toBeNull();
  });
});

describe("hashOf", () => {
  it("is stable, keyed, and does not contain the input", async () => {
    const a = await hashOf("secret", "203.0.113.7");
    expect(a).toBe(await hashOf("secret", "203.0.113.7"));
    expect(a).not.toBe(await hashOf("other", "203.0.113.7"));
    expect(a).not.toBe(await hashOf("secret", "203.0.113.8"));
    expect(a).not.toContain("203");
  });
});

describe("moving a subscription to a new phone", () => {
  const today = "2026-11-17";
  const pro = (over = {}) => ({ ...NEW_STATE, tier: "pro" as const, paidUntil: Date.parse("2026-12-17"), ...over });

  it("takes the usage already spent today along, so a new phone is not a fresh allowance", () => {
    const old = pro({ periodStart: today, periodTier: "pro", spent: 80_000, last: 5_000 });
    const fresh = pro();
    const moved = carryUsage(fresh, old, today);
    expect(moved.spent).toBe(80_000);
    expect(canUse(moved, today)).toBe(true);
    expect(canUse(carryUsage(pro(), pro({ periodStart: today, periodTier: "pro", spent: 95_000 }), today), today)).toBe(false);
  });

  it("does not carry a period that has already ended, or one from a different plan", () => {
    const ended = pro({ periodStart: "2026-11-10", periodTier: "pro", spent: 90_000 });
    expect(carryUsage(pro(), ended, today).spent).toBe(0);
    const wasFree = { ...NEW_STATE, periodStart: today, periodTier: "free" as const, spent: 60_000 };
    expect(carryUsage(pro(), wasFree, today).spent).toBe(0);
  });

  it("keeps the larger spend when both accounts are in the same period, and does nothing without a previous account", () => {
    const here = pro({ periodStart: today, periodTier: "pro", spent: 50_000 });
    const there = pro({ periodStart: today, periodTier: "pro", spent: 30_000 });
    expect(carryUsage(here, there, today).spent).toBe(50_000);
    expect(carryUsage(there, here, today).spent).toBe(50_000);
    expect(carryUsage(here, null, today)).toEqual(here);
  });
});
