// Google Play subscriptions: asks Google whether a purchase is still good and which plan it is.
import { googleAccessToken } from "./integrity";
import { Tier } from "./quota";

/** The subscription product IDs created in Play Console. They can never change once created. */
export const PRODUCT_TIERS: Record<string, Tier> = { pantrypal_pro: "pro", pantrypal_max: "max" };

export interface Entitlement {
  tier: Tier;
  paidUntil: number; // epoch ms
}

const TIER_RANK: Record<Tier, number> = { free: 0, pro: 1, max: 2 };

/**
 * Judges a Google "subscriptionsv2" response. Entitled while it is active, in a grace period, or cancelled but not yet
 * run out. Not entitled when pending, on hold, paused or expired. Pure, so it is tested with fixtures.
 */
export function entitlementFrom(payload: any, nowMs: number): Entitlement | null {
  const state: string = payload?.subscriptionState ?? "";
  const ok = state === "SUBSCRIPTION_STATE_ACTIVE" || state === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" || state === "SUBSCRIPTION_STATE_CANCELED";
  if (!ok) return null;
  let best: Entitlement | null = null;
  for (const item of payload?.lineItems ?? []) {
    const tier = PRODUCT_TIERS[item?.productId];
    const until = Date.parse(item?.expiryTime ?? "");
    if (!tier || !Number.isFinite(until) || until <= nowMs) continue;
    if (!best || TIER_RANK[tier] > TIER_RANK[best.tier] || (tier === best.tier && until > best.paidUntil)) best = { tier, paidUntil: until };
  }
  return best;
}

/** Fetches one purchase from the Google Play Developer API. */
export async function fetchSubscription(saJson: string, packageName: string, purchaseToken: string): Promise<any> {
  const access = await googleAccessToken(saJson, "https://www.googleapis.com/auth/androidpublisher");
  const url = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${packageName}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
  const res = await fetch(url, { headers: { authorization: `Bearer ${access}` } });
  if (!res.ok) throw new Error(`play ${res.status}`);
  return res.json();
}
