// Mirror of the app's ClaudePricing. Dollars per million tokens = micro-dollars per token.
const PRICES: Record<string, [number, number, number, number]> = {
  // input, output, cache read, cache write
  "claude-haiku-5-5": [0.1, 0.5, 0.01, 0.125],
  "claude-sonnet-5-5": [2.0, 10.0, 0.2, 2.5],
};
const WEB_SEARCH_MICROS = 10_000; // $10 per 1,000 searches

export interface Usage {
  input_tokens?: number;
  output_tokens?: number;
  cache_read_input_tokens?: number;
  cache_creation_input_tokens?: number;
  server_tool_use?: { web_search_requests?: number };
}

/** An unknown model is charged at the dearest known price, so limits err on the safe side. */
export function microDollars(model: string, usage: Usage | undefined): number {
  if (!usage) return 0;
  const p = PRICES[model] ?? PRICES["claude-sonnet-5-5"];
  const tokens =
    (usage.input_tokens ?? 0) * p[0] +
    (usage.output_tokens ?? 0) * p[1] +
    (usage.cache_read_input_tokens ?? 0) * p[2] +
    (usage.cache_creation_input_tokens ?? 0) * p[3];
  return Math.round(tokens) + (usage.server_tool_use?.web_search_requests ?? 0) * WEB_SEARCH_MICROS;
}

export const ALLOWED_MODELS = Object.keys(PRICES);
