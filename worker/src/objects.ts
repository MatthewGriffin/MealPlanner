import { DurableObject } from "cloudflare:workers";
import { NEW_STATE, QuotaState, Snapshot, Tier, canUse, expire, record, setPaid, setTier, snapshot } from "./quota";
import { entitlementFrom, fetchSubscription } from "./play";

export interface Env {
  USER_QUOTA: DurableObjectNamespace<UserQuota>;
  COUNTERS: DurableObjectNamespace<Counters>;
  PURCHASE_OWNER: DurableObjectNamespace<PurchaseOwner>;
  ANTHROPIC_API_KEY: string;
  TOKEN_SECRET: string;
  ADMIN_SECRET: string;
  UPSTREAM_URL: string;
  GLOBAL_DAILY_LIMIT_MICROS: string;
  REGISTRATIONS_PER_IP_PER_DAY: string;
  /** "off" (no check), "log" (check and log, never refuse) or "enforce" (refuse a failed check). */
  INTEGRITY_MODE?: string;
  PACKAGE_NAME?: string;
  /** Google service-account key JSON (secret) used to decode Play Integrity tokens. */
  GOOGLE_SA_JSON?: string;
}

/** One per user. A Durable Object handles one call at a time, so the counting cannot race. */
export class UserQuota extends DurableObject<Env> {
  private async raw(): Promise<QuotaState> {
    return { ...NEW_STATE, ...((await this.ctx.storage.get<QuotaState>("state")) ?? {}) };
  }

  /** The state with an expired paid plan counted as free. */
  private async load(): Promise<QuotaState> {
    return expire(await this.raw(), Date.now());
  }

  /** If a paid plan has run out and a purchase is on file, ask Google once whether it renewed. */
  private async renewIfDue(): Promise<void> {
    const now = Date.now();
    const state = await this.raw();
    if (!state.purchaseToken || state.paidUntil === null || now <= state.paidUntil) return;
    if (now - state.lastVerified < 10 * 60_000 || !this.env.GOOGLE_SA_JSON) return;
    let next: QuotaState = { ...state, lastVerified: now };
    try {
      const payload = await fetchSubscription(this.env.GOOGLE_SA_JSON, this.env.PACKAGE_NAME ?? "app.pantrypal.mealplanner", state.purchaseToken);
      const ent = entitlementFrom(payload, now);
      next = ent ? setPaid(state, ent.tier, ent.paidUntil, state.purchaseToken, now) : { ...next, purchaseToken: null };
    } catch {
      // Google could not be reached: stay on free until the next try, which is at least ten minutes away.
    }
    await this.ctx.storage.put("state", next);
  }

  /** A verified purchase for this account. */
  async setPaid(today: string, tier: Tier, paidUntil: number, token: string): Promise<Snapshot> {
    const next = setPaid(await this.raw(), tier, paidUntil, token, Date.now());
    await this.ctx.storage.put("state", next);
    return snapshot(expire(next, Date.now()), today);
  }

  async check(today: string): Promise<{ allowed: boolean; snapshot: Snapshot }> {
    await this.renewIfDue();
    const state = await this.load();
    return { allowed: canUse(state, today), snapshot: snapshot(state, today) };
  }

  async charge(today: string, micros: number): Promise<Snapshot> {
    const next = record(await this.load(), today, micros);
    await this.ctx.storage.put("state", next);
    return snapshot(next, today);
  }

  async setPlan(today: string, tier: Tier): Promise<Snapshot> {
    const next = setTier(await this.raw(), tier);
    await this.ctx.storage.put("state", next);
    return snapshot(next, today);
  }

  async view(today: string): Promise<Snapshot> {
    await this.renewIfDue();
    return snapshot(await this.load(), today);
  }
}

/** A named number that only goes up: registrations per IP per day, and the whole service's spend per day. */
export class Counters extends DurableObject<Env> {
  async add(amount: number): Promise<number> {
    const total = ((await this.ctx.storage.get<number>("n")) ?? 0) + amount;
    await this.ctx.storage.put("n", total);
    return total;
  }

  async get(): Promise<number> {
    return (await this.ctx.storage.get<number>("n")) ?? 0;
  }
}

/** One per purchase (named by a keyed hash of the purchase token): remembers which account holds it, so one purchase cannot unlock many installs. */
export class PurchaseOwner extends DurableObject<Env> {
  /** Makes [userId] the holder and returns the previous holder, if a different account had it. */
  async claim(userId: string): Promise<string | null> {
    const previous = (await this.ctx.storage.get<string>("owner")) ?? null;
    await this.ctx.storage.put("owner", userId);
    return previous && previous !== userId ? previous : null;
  }
}
