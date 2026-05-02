# Integrations Platform

A production-grade OAuth integration framework built with **Java 17 + Spring Boot 3.2**, **React**, and **Redis**. Connects to four third-party providers, loads structured data through their APIs, and protects outbound calls with **Resilience4j circuit breakers and retry**.

## Supported Integrations

| Integration | Auth Flow | Objects Loaded | Notes |
|-------------|-----------|----------------|-------|
| **Airtable** | OAuth 2.0 + PKCE | Bases, Tables | Hierarchical parent/child |
| **Notion** | OAuth 2.0 | Pages, Databases | Recursive property search |
| **HubSpot** | OAuth 2.0 | Contacts, Companies, Deals | CRM v3 with cursor pagination |
| **Stripe** | OAuth Connect | Customers, PaymentIntents, Charges | `has_more` pagination |

## Production Patterns

- **Circuit breaker** (Resilience4j) on every outbound data-loading call. If a provider API starts failing (>50% failure rate over a 10-call sliding window), the breaker opens for 30s and returns an empty list instead of cascading failures into the caller.
- **Retry with exponential backoff** (3 attempts, 500ms base, 2x multiplier) on the same calls, so transient 5xx errors from providers don't surface as user-visible failures.
- **CSRF state validation** on all OAuth callbacks. State tokens are generated with `SecureRandom`, stored in Redis with a 600s TTL, and verified on the return trip before any token exchange.
- **Ephemeral credential storage** -- OAuth tokens are written to Redis with a short TTL and deleted immediately after the frontend reads them. They never hit disk or a persistent database.
- **21 unit tests** across controllers (`@WebMvcTest`), services (Mockito), and models. Coverage includes happy paths, error paths, and edge cases (e.g., name fallback when HubSpot contact has no first/last name).

## Architecture

```
+--------------+      +----------------+      +-------+
|  React SPA   |----->| Spring Boot    |----->| Redis |
|  (MUI+Axios) |      |  REST API      |      |(state |
|  :3000       |<-----|  + Resilience4j |      |+creds)|
+--------------+      +----------------+      +-------+
                            |
                  +---------+---------+
                  v         v         v
              Airtable  HubSpot   Stripe
              Notion    CRM v3    Connect
```

## Prerequisites

- Java 17+
- Maven 3.8+ (or use the included `mvnw` wrapper)
- Node.js 16+
- Redis server

## Quick Start

```bash
# Terminal 1 -- Redis
redis-server

# Terminal 2 -- Backend
cd backend
./mvnw spring-boot:run          # starts on :8000

# Terminal 3 -- Frontend
cd frontend
npm install && npm start         # opens :3000
```

## Configuration

Copy `.env.example` to `.env` and fill in your OAuth credentials:

```bash
cp .env.example .env
```

Or export environment variables directly:

```bash
export HUBSPOT_CLIENT_ID=...
export HUBSPOT_CLIENT_SECRET=...
export STRIPE_CLIENT_ID=...        # Stripe Connect platform client ID (ca_...)
export STRIPE_API_KEY=...          # Stripe secret key (sk_...)
```

### Registering Provider Apps

| Provider | Console | Redirect URI |
|----------|---------|-------------|
| HubSpot | https://developers.hubspot.com | `http://localhost:8000/integrations/hubspot/oauth2callback` |
| Stripe | https://dashboard.stripe.com/settings/connect | `http://localhost:8000/integrations/stripe/oauth2callback` |
| Airtable | https://airtable.com/create/tokens | `http://localhost:8000/integrations/airtable/oauth2callback` |
| Notion | https://www.notion.so/my-integrations | `http://localhost:8000/integrations/notion/oauth2callback` |

## Running Tests

```bash
cd backend
./mvnw test
```

```
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Test breakdown:
- **Controller tests** (9): `@WebMvcTest` with `@MockBean` services. Verify routing, parameter binding, response content types (JSON vs HTML for callbacks), and error handling.
- **Service tests** (9): Mockito-based. Verify OAuth state management, Redis lifecycle (store/read/delete), API response parsing, and edge cases (blank names, missing fields).
- **Model tests** (3): Builder defaults, field mapping, equality contract.

## Project Structure

```
backend/
  pom.xml
  src/main/java/com/integrations/
    IntegrationsApplication.java
    config/
      WebConfig.java                    # CORS + RestTemplate bean
    model/
      IntegrationItem.java              # @Data @Builder (Lombok)
    controller/
      RootController.java               # GET / health check
      AirtableController.java
      NotionController.java
      HubSpotController.java
      StripeController.java
    service/
      RedisService.java                 # Thin Redis wrapper
      AirtableService.java              # PKCE OAuth + bases/tables
      NotionService.java                # OAuth + recursive search
      HubSpotService.java               # OAuth + CRM v3 (3 object types)
      StripeService.java                # Connect OAuth + charges/customers
  src/main/resources/
    application.properties              # Port, Redis, OAuth, Resilience4j
  src/test/java/com/integrations/
    controller/                         # @WebMvcTest tests
    service/                            # Mockito unit tests
    model/                              # Builder + equality tests
frontend/
  src/
    App.js
    integration-form.js                 # Dropdown + dynamic component
    data-form.js                        # Load Data + display
    integrations/
      airtable.js
      notion.js
      hubspot.js
      stripe.js

## License

MIT
```
