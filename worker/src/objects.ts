import { DurableObject } from "cloudflare:workers";
import { NEW_STATE, QuotaState, Snapshot, Tier, canUse, carryUsage, expire, record, setPaid, setTier, snapshot } from "./quota";
import { entitlementFrom, fetchSubscription } from "./play";

export interface Env {
  USER_QUOTA: DurableObjectNamespace<UserQuota>;
  COUNTERS: DurableObjectNamespace<Counters>;
  PURCHASE_OWNER: DurableObjectNamespace<PurchaseOwner>;
  DEVICE_OWNER: DurableObjectNamespace<DeviceOwner>;
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

const MAX_FOLLOWER_KEYS = 10;

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

  /** This account's stored state, so a subscription that moves to another account can take the usage so far with it. */
  async exportState(): Promise<QuotaState> {
    return this.raw();
  }

  /** A verified purchase for this account. [previous] is the account the subscription came from, if it moved. */
  async setPaid(today: string, tier: Tier, paidUntil: number, token: string, previous: QuotaState | null = null): Promise<Snapshot> {
    const next = carryUsage(setPaid(await this.raw(), tier, paidUntil, token, Date.now()), previous, today);
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

  /** Switches a follower's key on. At most MAX_FOLLOWER_KEYS at once. */
  async addFollowerKey(keyId: string): Promise<boolean> {
    const keys = (await this.ctx.storage.get<string[]>("fkeys")) ?? [];
    if (keys.includes(keyId)) return true;
    if (keys.length >= MAX_FOLLOWER_KEYS) return false;
    await this.ctx.storage.put("fkeys", [...keys, keyId]);
    return true;
  }

  async removeFollowerKey(keyId: string): Promise<void> {
    const keys = (await this.ctx.storage.get<string[]>("fkeys")) ?? [];
    await this.ctx.storage.put("fkeys", keys.filter((k) => k !== keyId));
  }

  async hasFollowerKey(keyId: string): Promise<boolean> {
    return ((await this.ctx.storage.get<string[]>("fkeys")) ?? []).includes(keyId);
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

/** One per phone (named by a keyed hash of its Android ID): remembers which account belongs to it, so a reinstall gets the same account back. */
export class DeviceOwner extends DurableObject<Env> {
  async get(): Promise<string | null> {
    return (await this.ctx.storage.get<string>("owner")) ?? null;
  }

  /** Makes [userId] the owner if nobody is yet, and returns whoever the owner is. */
  async claim(userId: string): Promise<string> {
    const existing = await this.get();
    if (existing) return existing;
    await this.ctx.storage.put("owner", userId);
    return userId;
  }
}
