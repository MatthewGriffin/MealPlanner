import { describe, expect, it } from "vitest";
import { entitlementFrom } from "../src/play";
import { NEW_STATE, canUse, expire, record, setPaid, setTier, snapshot } from "../src/quota";

const NOW = Date.parse("2026-11-16T12:00:00Z");
const sub = (state: string, items: { productId: string; expiryTime: string }[]) => ({ subscriptionState: state, lineItems: items });
const future = "2026-12-16T12:00:00Z";
const past = "2026-11-01T12:00:00Z";

describe("entitlement", () => {
  it("grants the plan while active, in grace, or cancelled but not yet run out", () => {
    for (const st of ["ACTIVE", "IN_GRACE_PERIOD", "CANCELED"]) {
      expect(entitlementFrom(sub("SUBSCRIPTION_STATE_" + st, [{ productId: "pantrypal_pro", expiryTime: future }]), NOW)).toEqual({ tier: "pro", paidUntil: Date.parse(future) });
    }
  });
  it("refuses pending, on hold, paused, expired and unknown", () => {
    for (const st of ["PENDING", "ON_HOLD", "PAUSED", "EXPIRED", "SOMETHING_NEW"]) {
      expect(entitlementFrom(sub("SUBSCRIPTION_STATE_" + st, [{ productId: "pantrypal_pro", expiryTime: future }]), NOW)).toBeNull();
    }
    expect(entitlementFrom(undefined, NOW)).toBeNull();
  });
  it("refuses an unknown product or a past expiry, and picks the best line", () => {
    expect(entitlementFrom(sub("SUBSCRIPTION_STATE_ACTIVE", [{ productId: "other", expiryTime: future }]), NOW)).toBeNull();
    expect(entitlementFrom(sub("SUBSCRIPTION_STATE_ACTIVE", [{ productId: "pantrypal_pro", expiryTime: past }]), NOW)).toBeNull();
    const both = sub("SUBSCRIPTION_STATE_ACTIVE", [{ productId: "pantrypal_pro", expiryTime: future }, { productId: "pantrypal_max", expiryTime: future }]);
    expect(entitlementFrom(both, NOW)?.tier).toBe("max");
  });
});

describe("paid plan expiry", () => {
  const D = "2026-11-16";
  it("counts as free once the end date passes, with a fresh free allowance", () => {
    let s = setPaid(NEW_STATE, "pro", NOW + 1000, "tok", NOW);
    s = record(s, D, 80_000);
    expect(snapshot(expire(s, NOW + 500), D).tier).toBe("pro");
    const later = expire(s, NOW + 2000);
    expect(later.tier).toBe("free");
    expect(canUse(later, D)).toBe(true); // pro spend does not carry into the free week
  });
  it("renewal keeps the allowance already used this period", () => {
    let s = setPaid(NEW_STATE, "pro", NOW + 1000, "tok", NOW);
    s = record(s, D, 80_000);
    const renewed = setPaid(s, "pro", NOW + 100_000, "tok", NOW + 2000);
    expect(canUse(renewed, D)).toBe(true);
    expect(record(renewed, D, 20_000).spent).toBe(100_000);
  });
  it("a hand-set plan has no end date and no purchase", () => {
    const s = setTier(setPaid(NEW_STATE, "pro", NOW + 1000, "tok", NOW), "max");
    expect(s.paidUntil).toBeNull();
    expect(s.purchaseToken).toBeNull();
    expect(expire(s, NOW + 10 ** 12).tier).toBe("max");
  });
});
