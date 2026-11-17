// Mirror of the app's UsageQuota. Amounts are millionths of a dollar of Claude cost.
// Free: a weekly bundle that starts at the first use. Pro and Max: a daily allowance. Neither is unlimited.

export type Tier = "free" | "pro" | "max";

export const FREE_LIMIT = 70_000; // 7 cents a week
export const PRO_LIMIT = 90_000; // 9 cents a day, about 20 meal requests
export const MAX_LIMIT = 180_000; // double Pro

export function limitFor(tier: Tier): number {
  return tier === "free" ? FREE_LIMIT : tier === "pro" ? PRO_LIMIT : MAX_LIMIT;
}

export function periodDays(tier: Tier): number {
  return tier === "free" ? 7 : 1;
}

export interface QuotaState {
  tier: Tier;
  periodStart: string | null; // YYYY-MM-DD (UTC)
  periodTier: Tier | null;
  spent: number;
  last: number;
  /** When a paid plan runs out (epoch ms). Null = no end date (set by hand with /admin/tier, or on the free plan). */
  paidUntil: number | null;
  /** The Google Play purchase behind the plan, kept so a renewal can be checked without the app. */
  purchaseToken: string | null;
  /** When Google was last asked about that purchase (epoch ms). */
  lastVerified: number;
}

export const NEW_STATE: QuotaState = {
  tier: "free", periodStart: null, periodTier: null, spent: 0, last: 0,
  paidUntil: null, purchaseToken: null, lastVerified: 0,
};

/** A paid plan whose end date has passed counts as free; the period check then gives a fresh free week. */
export function expire(state: QuotaState, nowMs: number): QuotaState {
  return state.tier !== "free" && state.paidUntil !== null && nowMs > state.paidUntil ? { ...state, tier: "free" } : state;
}

/** A verified purchase: sets the plan and its end date. Renewing the same plan keeps the current allowance period. */
export function setPaid(state: QuotaState, tier: Tier, paidUntil: number, token: string, nowMs: number): QuotaState {
  return { ...state, tier, paidUntil, purchaseToken: token, lastVerified: nowMs };
}

/**
 * The subscription moved here from another account (a new phone): the allowance already used this period moves with it,
 * so switching phones does not hand out a fresh one. If both accounts are in the same active period, the larger spend wins.
 */
export function carryUsage(next: QuotaState, previous: QuotaState | null, today: string): QuotaState {
  if (!previous) return next;
  const had = activePeriodStart({ ...previous, tier: next.tier }, today);
  if (!had) return next;
  const here = activePeriodStart(next, today);
  if (here && next.spent >= previous.spent) return next;
  return { ...next, periodStart: previous.periodStart, periodTier: previous.periodTier, spent: previous.spent, last: previous.last };
}

export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** The day the current period started, or null if it has ended, never started, or the plan changed since. */
export function activePeriodStart(state: QuotaState, today: string): string | null {
  if (!state.periodStart || state.periodTier !== state.tier) return null;
  return today < addDays(state.periodStart, periodDays(state.tier)) ? state.periodStart : null;
}

export function spentNow(state: QuotaState, today: string): number {
  return activePeriodStart(state, today) ? state.spent : 0;
}

/** True while the allowance for this period is not used up. A request that starts under the limit may finish over. */
export function canUse(state: QuotaState, today: string): boolean {
  return spentNow(state, today) < limitFor(state.tier);
}

export function record(state: QuotaState, today: string, micros: number): QuotaState {
  if (micros <= 0) return state;
  const start = activePeriodStart(state, today);
  return {
    ...state,
    periodStart: start ?? today,
    periodTier: state.tier,
    spent: (start ? state.spent : 0) + micros,
    last: micros,
  };
}

/** A hand-set plan (admin): no end date and no purchase behind it. */
export function setTier(state: QuotaState, tier: Tier): QuotaState {
  return { ...state, tier, paidUntil: null, purchaseToken: null };
}

export interface Snapshot {
  tier: Tier;
  percentLeft: number;
  resetsOn: string | null;
}

export function snapshot(state: QuotaState, today: string): Snapshot {
  const limit = limitFor(state.tier);
  const spent = spentNow(state, today);
  const start = activePeriodStart(state, today);
  return {
    tier: state.tier,
    percentLeft: Math.floor((Math.max(0, limit - spent) * 100) / limit),
    resetsOn: start ? addDays(start, periodDays(state.tier)) : null,
  };
}
