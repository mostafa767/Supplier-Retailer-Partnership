# Supplier & Retailer Partnership Portal

Equadis technical test - **design document** and **proof of concept**.

Suppliers and retailers of several **marketplaces** work together through time-bound partnerships. Suppliers publish
discount offers (one by one or as CSV files of up to 100,000 lines); partnered retailers browse them, subscribe to
them, and are informed of new ones by e-mail digest or signed webhook. Marketplaces never see each other's data.

| Deliverable | Where |
|---|---|
| Design document (Part A) | [`docs/Design_Document.docx`](docs/Design_Document.docx) |
| Diagrams | [`docs/diagrams/`](docs/diagrams) |
| Edge cases, performance and best-practice decisions | [`EDGE_CASES_AND_DECISIONS.md`](EDGE_CASES_AND_DECISIONS.md) |
| Proof of concept (Part B) | this repository - Java 21, Spring Boot 3.5, PostgreSQL 16 |

---

## 1. What the proof of concept proves

| Requirement | Behaviour | How |
|---|---|---|
| **R3** Offers from supplier systems, as a CSV file or a JSON API call | Errors on some lines never block the others; every failed line is reported with its position and reason; the same offers sent twice create no duplicates | One async chunked pipeline for both formats, SHA-256 idempotency, upsert by (supplier, reference), error report (JSON + CSV) |
| **R7** Who changed what and when | Every change is recorded with its author, before/after values and the request's correlation id; the application cannot alter the history | Append-only `audit_event`, written in the same transaction as the change |
| **R5** Informing retailers | Publishing an offer informs exactly the retailers with an ACTIVE partnership, by e-mail digest and signed webhook | Transactional outbox → coalesced notifications → digest computed at send time |
| **R1** Two marketplaces never mixed | A retailer of marketplace A never sees B's data, even if a query forgets its filter | PostgreSQL Row-Level Security, non-owner DB role, composite foreign keys |
| R6 / BR-03…BR-11 | Termination cancels subscriptions and removes access instantly, including through a renewal waiting to start; no overlapping partnerships even under concurrency; one active subscription per offer | Domain rules + database constraints + row locks |

**154 automated tests:** 88 unit tests (no Docker) and 66 integration tests against a real PostgreSQL.

---

## 2. Run it (one command)

**Prerequisite:** Docker Desktop (or Docker Engine with Compose v2).

```bash
docker compose up --build
```

This starts three containers:

| Service | URL | What |
|---|---|---|
| `app` | http://localhost:8080 | the API (migrations and demo data are applied at start-up) |
| `mailpit` | http://localhost:8025 | a local mail inbox: digest e-mails arrive here |
| `postgres` | localhost:5432 | PostgreSQL 16 (owner `portal_owner` / `portal_owner_dev_password`, db `portal`) |

The application is ready when `curl http://localhost:8080/actuator/health` returns `{"status":"UP"}`.
Stop with `Ctrl+C`; reset all data with `docker compose down -v`.

> Digest e-mails are sent **30 seconds** after the first publication in the demo (1 hour by default in production,
> see `PORTAL_NOTIFICATIONS_EMAIL_DIGEST_DELAY` in `docker-compose.yml`).

### Run without Docker (optional)

Needs Java 21 and a PostgreSQL 16 whose user can create roles. Secrets have no default value: the application refuses
to start without them.

```bash
PORTAL_DB_URL="jdbc:postgresql://localhost:5432/portal?reWriteBatchedInserts=true" \
PORTAL_OWNER_DB_USER=postgres PORTAL_OWNER_DB_PASSWORD=postgres \
PORTAL_APP_DB_PASSWORD=choose-one \
PORTAL_WEBHOOK_SECRET_KEY="$(openssl rand -base64 32)" \
./mvnw spring-boot:run
```

---

## 3. Try it: a 5-minute walkthrough

The demo data (`src/main/resources/db/seed/V100__demo_data.sql`) contains two marketplaces with fixed ids:

| | Marketplace A - Swiss Pharmacy Network | Marketplace B - French Food Alliance |
|---|---|---|
| Marketplace id | `11111111-1111-1111-1111-111111111111` | `22222222-2222-2222-2222-222222222222` |
| Suppliers | Nivea `a1000000-0000-0000-0000-000000000001`, La Roche-Posay `…0002` | Danone `b1000000-0000-0000-0000-000000000001`, Nivea (separate account) `…0002` |
| Retailers | Pharmacie du Lac `a2000000-0000-0000-0000-000000000001`, PharmaOnline `…0002` | Epicerie Martin `b2000000-0000-0000-0000-000000000001`, FreshBox `…0002` |
| Partnerships | Nivea ↔ both retailers ACTIVE; La Roche-Posay ↔ PharmaOnline SCHEDULED | Danone ↔ both retailers ACTIVE; Nivea ↔ FreshBox ACTIVE |

**Identity in the PoC.** A real deployment reads the caller from an OAuth2 token (Keycloak). The PoC uses three
headers instead - only `ActorContextInterceptor` would change:
`X-Marketplace-Id`, `X-Actor-Role` (`SUPPLIER` | `RETAILER` | `MARKETPLACE_ADMIN`), `X-Actor-Id`.

The commands below are for **bash** (Linux, macOS, Git Bash on Windows). In PowerShell, use `curl.exe` and backticks
for line continuation.

```bash
API=http://localhost:8080
MP_A=11111111-1111-1111-1111-111111111111
MP_B=22222222-2222-2222-2222-222222222222
NIVEA=a1000000-0000-0000-0000-000000000001
LAC=a2000000-0000-0000-0000-000000000001
MARTIN=b2000000-0000-0000-0000-000000000001

AS_NIVEA=(-H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: SUPPLIER" -H "X-Actor-Id: $NIVEA")
AS_LAC=(-H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: RETAILER" -H "X-Actor-Id: $LAC")
AS_MARTIN=(-H "X-Marketplace-Id: $MP_B" -H "X-Actor-Role: RETAILER" -H "X-Actor-Id: $MARTIN")
```

### R1 - two marketplaces, never mixed

```bash
curl -s $API/api/catalogue "${AS_LAC[@]}"      # Nivea offers of marketplace A only
curl -s $API/api/catalogue "${AS_MARTIN[@]}"   # Danone offer of marketplace B only
```

### R3 - import a file with errors

```bash
curl -s -i $API/api/imports "${AS_NIVEA[@]}" -F "file=@samples/offers-with-errors.csv"
# -> 202 Accepted, Location: /api/imports/{id}. Copy the id:
IMPORT=<id>

curl -s $API/api/imports/$IMPORT "${AS_NIVEA[@]}"                 # COMPLETED: created 2, failed 5
curl -s "$API/api/imports/$IMPORT/errors" "${AS_NIVEA[@]}"         # every problem, with line numbers
curl -s "$API/api/imports/$IMPORT/errors.csv" "${AS_NIVEA[@]}"     # same report as CSV

# Send the SAME file again: 200 OK, same id, "alreadySubmitted": true - nothing imported twice.
curl -s $API/api/imports "${AS_NIVEA[@]}" -F "file=@samples/offers-with-errors.csv"
```

Expected error report (lines are counted like in a spreadsheet, header = line 1):

```
line,item,reference,field,code,message
3,,NIV-ERR-02,discount_value,DISCOUNT_OUT_OF_RANGE,"A percentage must be greater than 0 and at most 100, got 150"
4,,NIV-ERR-03,currency,CURRENCY_REQUIRED,"A fixed amount requires an ISO 4217 currency (e.g. EUR, CHF)"
5,,NIV-ERR-04,discount_value,DISCOUNT_VALUE_INVALID,"'12,5' is not a number; use a dot as decimal separator (e.g. 12.50)"
5,,NIV-ERR-04,valid_from,DATE_INVALID,'01/11/2026' is not a valid date; use yyyy-MM-dd (e.g. 2026-11-27)
6,,NIV-ERR-05,valid_to,VALIDITY_WINDOW_INVALID,Valid-to date 2026-11-01 must be on or after valid-from date 2027-03-31
7,,NIV-ERR-01,reference,DUPLICATE_REFERENCE_IN_FILE,Reference NIV-ERR-01 already appears at line 2; ...
```

### R3 - the same import through the API (JSON)

```bash
curl -s -i $API/api/imports "${AS_NIVEA[@]}" -H "Content-Type: application/json" -d '{"offers": [
  {"reference": "API-01", "title": "Hand cream", "discountType": "PERCENTAGE", "discountValue": 20,
   "validFrom": "2026-11-01", "validTo": "2027-01-31", "publish": true},
  {"reference": "API-02", "title": "Too generous", "discountType": "PERCENTAGE", "discountValue": 150,
   "validFrom": "2026-11-01", "validTo": "2027-01-31"}
]}'
# 202 + Location. Same job, same report; problems point to the item: "itemIndex": 1, "field": "discountValue".

# Check a file BEFORE importing it: what would be created or updated, problems grouped by code. Nothing is stored.
curl -s $API/api/imports/dry-run "${AS_NIVEA[@]}" -F "file=@samples/offers-with-errors.csv"
```

Both formats go through **inbound connectors** (`OfferSourceAdapter`: `CsvOfferSource`, `JsonOfferSource`) that turn the
offers into one canonical form; everything after - checks, dry run, report, duplicate protection, recovery - is shared.
A new format (Excel matrix, GDSN) is one more connector.

### R7 - who changed what and when

```bash
curl -s "$API/api/audit-events?entityId=$IMPORT" \
     -H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: MARKETPLACE_ADMIN" -H "X-Actor-Id: 00000000-0000-0000-0000-0000000000ad"
# IMPORT_SUBMITTED, then one OFFERS_IMPORTED per chunk: who, when, what, and the correlation id of the request.
```

### R5 - publish offers and watch retailers being informed

```bash
# A file that publishes 3 offers (publish=true) ...
curl -s $API/api/imports "${AS_NIVEA[@]}" -F "file=@samples/offers-valid.csv"

# ... and one draft published through the API (seeded draft NIV-DRAFT-03)
curl -s -X POST $API/api/offers/a4000000-0000-0000-0000-000000000003/publication "${AS_NIVEA[@]}"
```

Open **http://localhost:8025** - within ~30 seconds, **one** digest per partner retailer (Pharmacie du Lac and
PharmaOnline) arrives, listing all new offers together. Retailers of marketplace B receive nothing.

### More

```bash
# Subscribe (idempotent: 201 the first time, 200 afterwards)
curl -s -i -X POST $API/api/offers/a4000000-0000-0000-0000-000000000001/subscriptions "${AS_LAC[@]}"

# Terminate the partnership (R6): subscriptions cancelled, offers disappear from the catalogue
curl -s -X POST $API/api/partnerships/a3000000-0000-0000-0000-000000000001/termination \
     -H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: MARKETPLACE_ADMIN" -H "X-Actor-Id: 00000000-0000-0000-0000-0000000000ad"
curl -s $API/api/catalogue "${AS_LAC[@]}"   # -> empty

# Errors are consistent Problem Details, with a correlation id to find the logs
curl -s -i $API/api/catalogue
```

---

## 4. Test it

| Command | Runs | Needs |
|---|---|---|
| `./mvnw test` | 88 unit tests (domain rules, CSV parsing, signing, encryption, SSRF policy, architecture rules...) | Java 21 |
| `./mvnw verify` | unit **and** 66 integration tests (whole application + real PostgreSQL via Testcontainers) | Java 21 + Docker |

On Windows use `mvnw.cmd`. Reports: `target/surefire-reports` (unit) and `target/failsafe-reports` (integration).

**Without Docker**, the integration tests can use an existing **empty** PostgreSQL 16 database whose user can create
roles:

```bash
PORTAL_TEST_DB_URL=jdbc:postgresql://localhost:5432/portal_test \
PORTAL_TEST_DB_USER=postgres PORTAL_TEST_DB_PASSWORD=postgres ./mvnw verify
```

Why a real PostgreSQL and not H2: Row-Level Security, the exclusion constraint (BR-04), partial unique indexes and
`ON CONFLICT` only exist in PostgreSQL - and they are exactly the behaviour under test. The tests also connect as the
application's non-owner role, as in production.

| Test class | Proves |
|---|---|
| `MarketplaceIsolationIT` | R1: API isolation, RLS without WHERE clause, fail-closed, writes into another marketplace rejected |
| `OfferCsvImportIT` | R3: partial errors, same file twice, updates vs unchanged, BR-08, duplicates in file, Excel files, re-queue, CSV injection |
| `OfferNotificationIT` | R5: outbox atomicity, only active partners, coalescing into one digest, termination before sending, signed webhook, retries, 4xx |
| `NotificationLeaseIT` | R5 under slow receivers: a batch stops before its lease expires, nothing is sent twice, no retry is used up |
| `BulkImportApiIT` | R3 through the API: partial errors by item index, same request twice, wrong shapes refused, duplicates inside a request |
| `AuditTrailIT` | R7: who, what (before/after), when, correlation id; refused changes leave nothing; append-only enforced by the database |
| `RobustnessIT` | hostile input stays a line error or a 4xx: huge numbers, NUL characters, impossible dates, long cells; redirecting webhooks; BR-10 in digests |
| `ArchitectureTest` | the layers: controllers never reach repositories, SQL exists only in repositories |
| `PartnershipAndSubscriptionIT` | R6, BR-04 under concurrency, BR-03, BR-06, BR-10, BR-11 |
| `ApiErrorHandlingIT` | middleware and error contract: 401/403/404/409/422, correlation id, all violations reported |
| `*Test` (unit) | BR-03/05/06/07 (`PartnershipTest`), BR-08/09 (`OfferTest`, `OfferTermsTest`), CSV parsing and inspection, retry policy, cursor, signing |

---

## 5. How it is built

```
src/main/java/com/equadis/portal
├── tenant/         Actor, TenantContext, ActorContextInterceptor (identity middleware), TenantAwareDataSource (RLS)
├── partnership/    Partnership (BR-03..07), controller, service, repository
├── offer/          Offer (BR-08), OfferTerms (BR-09, shared by API and CSV), controller, service, repository
├── catalogue/      BR-10 query with keyset pagination (R4): controller, service, repository
├── subscription/   BR-11, cancellation on termination (BR-07): controller, service, repository
├── importing/      CSV and JSON imports: checks, idempotency, chunked processing, error report (R3)
├── outbox/         transactional outbox: writer, dispatcher, repository
├── audit/          who changed what and when (R7): controller, service, repository
├── notification/   fan-out with coalescing, digest, e-mail and webhook channels, sender (R5)
├── directory/      read access to suppliers / retailers, encryption of webhook secrets
├── scheduling/     what triggers the background work
└── shared/         error handling (Problem Details), request tracing middleware, Clock, work claims, retry
src/main/resources/db/migration   Flyway: schema, RLS, worker claim functions, limits, encrypted secrets
src/main/resources/db/seed        demo data (not used by tests)
```

**Layers.** Every module follows the same rule, enforced by `ArchitectureTest`:

| Layer | Classes | Responsibility |
|---|---|---|
| Entry points | `*Controller`, background workers | Translate HTTP or a schedule into a use case. No business logic |
| Services | `*Service`, `NotificationSender`, `OutboxDispatcher`, `ImportJobProcessor`... | Use cases, rules, authorization, transactions |
| Repositories | `*Repository` | The only classes that contain SQL |

Modules call each other only through public services, never through another module's repository. Read queries such
as the catalogue and the digest are the deliberate exception on the data side: they join several modules' tables,
because one SQL statement is what makes them fast.

**Request pipeline:** `RequestTracingFilter` (correlation id in logs and response) → `RequestSizeLimitFilter`
(bodies ≤ 1 MB, imports ≤ 50 MB, counted while reading) → `ActorContextInterceptor` (identity + marketplace) →
controller → service → `GlobalExceptionHandler` (one error format).

**Error format** (RFC 9457 Problem Details):

```json
{
  "type": "urn:equadis:portal:error:OFFER_NOT_EDITABLE",
  "title": "Offer not editable",
  "status": 422,
  "detail": "Offer SUN-01 is PUBLISHED; only DRAFT offers can be edited",
  "instance": "/api/offers/6f1c…",
  "code": "OFFER_NOT_EDITABLE",
  "businessRule": "BR-08",
  "correlationId": "b0b5f2d7-…",
  "timestamp": "2026-11-02T09:15:00Z"
}
```

Every log line carries `[cid=<correlation id> mp=<marketplace id>]`; background work uses `import-<id>`,
`outbox-<id>`, `notification-<id>` as correlation ids. Searching one id gives the whole story.

### API summary

| Method & path | Role | Purpose |
|---|---|---|
| `POST /api/imports` (multipart `file`) | SUPPLIER | Submit a CSV file → 202 (new) / 200 (same file already sent) |
| `POST /api/imports` (JSON `{"offers": [...]}`) | SUPPLIER | Same import through the API, up to 100,000 offers |
| `GET /api/imports/{id}` | SUPPLIER | Progress, counters and problems grouped by code (`errorSummary`) |
| `GET /api/imports/{id}/errors?afterId=&size=` | SUPPLIER | Line-by-line report (keyset pagination) |
| `GET /api/imports/{id}/errors.csv` | SUPPLIER | Report as CSV |
| `POST /api/offers` | SUPPLIER | Create a draft |
| `GET /api/offers/{id}` · `PUT /api/offers/{id}` | SUPPLIER | Read · edit a draft (optimistic locking with `version`) |
| `POST /api/offers/{id}/publication` · `/closure` | SUPPLIER | Publish · close |
| `GET /api/catalogue?supplierId=&publishedSince=&q=&size=&cursor=` | RETAILER | Visible offers (BR-10) |
| `POST /api/offers/{id}/subscriptions` · `GET /api/subscriptions` | RETAILER | Subscribe (idempotent) · list |
| `POST /api/partnerships` | MARKETPLACE_ADMIN | Create (BR-03, BR-04) |
| `POST /api/partnerships/{id}/termination` | admin or party | Terminate (BR-07, R6) |
| `POST /api/partnerships/{id}/renewal` | MARKETPLACE_ADMIN | Renew (BR-06) |
| `GET /api/audit-events?entityId=` | MARKETPLACE_ADMIN | History of an offer, partnership, subscription or import (R7) |
| `GET /actuator/health` · `/actuator/prometheus` | - | Probes · metrics |

### CSV format

```
reference,title,description,discount_type,discount_value,currency,valid_from,valid_to,publish
SUN-01,Sun cream summer deal,,PERCENTAGE,15,,2026-06-01,2026-08-31,true
LOT-02,Body lotion,"Bundle, 2 bottles",FIXED_AMOUNT,5.50,CHF,2026-06-01,2026-08-31,false
```

- UTF-8 (BOM accepted); comma **or** semicolon; columns matched by name, any order, case-insensitive.
- Required: `reference, title, discount_type, discount_value, valid_from, valid_to`. Optional: `description, currency, publish`.
- `discount_type`: `PERCENTAGE` (]0, 100], no currency) or `FIXED_AMOUNT` (> 0, ISO 4217 currency). Decimals with a dot.
- Dates `yyyy-MM-dd` (Europe/Paris calendar), years 1900 to 9999. `publish`: `true`/`false` (default `false`).
- Reported line numbers are the real lines of the file, blank lines included.
- References are case-insensitive. A file **adds or updates** offers; it never deletes missing ones.
- Max 100,000 rows and 50 MB per file.

### Configuration

All values are in `src/main/resources/application.yml` and can be overridden by environment variables
(e.g. `PORTAL_NOTIFICATIONS_EMAIL_DIGEST_DELAY=PT5M`). The main knobs: import chunk size and retries, digest windows,
retry back-off, webhook timeouts, worker polling intervals, `portal.workers.enabled` (run an instance as API-only or
worker-only).

---

## 6. Simplifications (deliberate)

- No user interface (allowed by the brief); identity through headers instead of OAuth2 tokens.
- Suppliers, retailers and marketplaces are seeded (no CRUD endpoints).
- Workers run inside the application; production runs them on dedicated instances of the same image.
- Daily extracts (R9), branding (R10), dedicated cells: designed in the document, not built.
- The audit trail is in the main database; production also copies it to write-once storage.

---

## 7. Use of AI assistants

I used an AI coding assistant (Claude) as a pair programmer: to challenge the design (edge cases, failure scenarios,
volume arithmetic), to draft code, tests and documentation, and to review them. I reviewed every design decision,
trade-off and line of code, and I understand and own them. All tests were run against a real PostgreSQL.
