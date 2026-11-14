# Pantry Planner proxy (Cloudflare Worker)

Sits between the app and Claude so the Claude key never ships in the app and each install has its own AI allowance.

- `POST /v1/register` gives an install an account token (limited per IP per day).
- `POST /v1/messages` is the Anthropic Messages API. The app's Claude client points here and sends its token as the API key. The Worker checks the allowance, forwards to Claude with the real key, charges the real token cost, and returns Claude's reply plus `x-pp-tier`, `x-pp-percent-left`, `x-pp-resets-on` headers. Over the limit it answers 402.
- `GET /v1/usage` returns the same numbers without making a request.
- `POST /admin/tier` (admin secret) sets an account to free, pro or max. The billing webhook will call this later.

Allowances (see `src/quota.ts`, mirrors the app): Free 7c a week from first use; Pro 9c a day; Max 18c a day.
A whole-service daily spend cap (`GLOBAL_DAILY_LIMIT_MICROS`) stops everything if fake accounts ever run up a bill.

## Deploy (one time)
```
cd worker
npm install
npx wrangler login
npx wrangler secret put ANTHROPIC_API_KEY   # your Claude key
npx wrangler secret put TOKEN_SECRET        # e.g. output of: openssl rand -base64 48
npx wrangler secret put ADMIN_SECRET        # e.g. output of: openssl rand -base64 48
npx wrangler deploy
```
Wrangler prints the Worker URL (https://pantry-planner-proxy.<your-subdomain>.workers.dev). Durable Objects with SQLite storage are available on the Workers free plan; check Cloudflare's current limits.

## Local test
Put the three secrets and `UPSTREAM_URL` in `.dev.vars` (git-ignored), then `npx wrangler dev`. `npm test` runs the quota, pricing and token tests.

## Keep in sync with the app
`src/quota.ts` and `src/pricing.ts` mirror `UsageQuota.kt` and `ClaudePricing.kt`. Change a limit or price in both.
