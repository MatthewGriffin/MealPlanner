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
}

export const NEW_STATE: QuotaState = { tier: "free", periodStart: null, periodTier: null, spent: 0, last: 0 };

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

export function setTier(state: QuotaState, tier: Tier): QuotaState {
  return { ...state, tier };
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
