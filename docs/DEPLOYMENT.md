# Production Deployment & Environment Specification

This document details backend server configuration, environment variables, health endpoints, and release build flags.

---

## 1. Backend Environment Variables

- `PORT`: Ktor server port (default 8080).
- `OPENAI_API_KEY`: Secret vendor key (never committed; environment variable only).
- `OPENAI_MODEL`: Active model (`gpt-4o-mini`).
- `AI_SYSTEM_PROMPT_VERSION`: Active prompt version (`meal-planner-system-v2`).
- `CONTRACT_VERSION`: Active AI contract version (`ai-contract-v2`).
- `DEV_AUTH_TOKEN`: Dev key (disabled in production mode).

---

## 2. Health Endpoints

- `GET /health`: Returns server status and system prompt version.
- `GET /v1/retail/capabilities`: Returns capability metadata for Iceland, Asda, and Food Warehouse.
