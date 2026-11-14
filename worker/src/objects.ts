import { DurableObject } from "cloudflare:workers";
import { NEW_STATE, QuotaState, Snapshot, Tier, canUse, record, setTier, snapshot } from "./quota";

export interface Env {
  USER_QUOTA: DurableObjectNamespace<UserQuota>;
  COUNTERS: DurableObjectNamespace<Counters>;
  ANTHROPIC_API_KEY: string;
  TOKEN_SECRET: string;
  ADMIN_SECRET: string;
  UPSTREAM_URL: string;
  GLOBAL_DAILY_LIMIT_MICROS: string;
  REGISTRATIONS_PER_IP_PER_DAY: string;
}

/** One per user. A Durable Object handles one call at a time, so the counting cannot race. */
export class UserQuota extends DurableObject<Env> {
  private async load(): Promise<QuotaState> {
    return (await this.ctx.storage.get<QuotaState>("state")) ?? NEW_STATE;
  }

  async check(today: string): Promise<{ allowed: boolean; snapshot: Snapshot }> {
    const state = await this.load();
    return { allowed: canUse(state, today), snapshot: snapshot(state, today) };
  }

  async charge(today: string, micros: number): Promise<Snapshot> {
    const next = record(await this.load(), today, micros);
    await this.ctx.storage.put("state", next);
    return snapshot(next, today);
  }

  async setPlan(today: string, tier: Tier): Promise<Snapshot> {
    const next = setTier(await this.load(), tier);
    await this.ctx.storage.put("state", next);
    return snapshot(next, today);
  }

  async view(today: string): Promise<Snapshot> {
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
