# Edge cases, performance and best-practice decisions

Companion to `docs/Design_Document.docx`. Everything below is also covered in the design document; this file is the
checklist view. **Status:** ✅ implemented and tested in the proof of concept · 🧩 implemented, covered indirectly ·
📐 designed (described in the design document, not built in the PoC).

---

## 1. CSV import (R3)

| # | Edge case | Decision | Status / where |
|---|-----------|----------|----------------|
| 1 | Empty file, or header only | Rejected at upload (`IMPORT_FILE_EMPTY`, 422), no job created | ✅ `OfferCsvInspectorTest.headerOnly_isEmpty` |
| 2 | Missing / misspelled required column | Whole file rejected at upload, missing columns listed (`IMPORT_FILE_INVALID_HEADER`) | ✅ `brokenHeader_rejectedImmediately` |
| 3 | Columns in a different order, different case | Columns matched by name, case-insensitively | ✅ `excelStyleFile_isAccepted` |
| 4 | Semicolon separator (French Excel) | Delimiter detected from the header line | ✅ `excelStyleFile_isAccepted` |
| 5 | UTF-8 with BOM | BOM skipped | ✅ `excelStyleFile_isAccepted` |
| 6 | Latin-1 / Windows-1252 file | Rejected (`IMPORT_FILE_NOT_UTF8`) instead of silently corrupting accents | ✅ `latin1File_isRefusedInsteadOfSilentlyCorrupted` |
| 7 | Quoted values with commas, quotes, apostrophes (`"Crème ""L'Oréal"""`) | Real CSV parser (Commons CSV), never `split(",")` | ✅ `excelStyleFile_isAccepted`, `validLine_becomesACandidate` |
| 8 | Unclosed quote / unreadable structure | Rejected at upload (`IMPORT_FILE_UNREADABLE`) | ✅ `unclosedQuote_isUnreadable` |
| 9 | More than 100,000 rows | Rejected at upload (`IMPORT_FILE_TOO_LARGE`, 413); upload size limit 50 MB | ✅ `tooManyRows_isRefused` |
| 10 | Decimal comma `12,5` | Line error with a helpful message (use a dot) | ✅ `decimalComma_isRefusedWithAHelpfulMessage` |
| 11 | Date `31/12/2026` | Line error: ISO `yyyy-MM-dd` only, value echoed | ✅ `parsingErrorsAreReportedWithTheReceivedValue…` |
| 12 | Several problems on one line | **All** reported, one row per problem | ✅ `partialErrors_validLinesImported_invalidLinesReported` |
| 13 | Field that failed parsing also "required" | Not reported twice | ✅ `parsingErrorsAreReported…_andNotDuplicatedAsRequired` |
| 14 | Line with too few / too many values | `COLUMN_COUNT_MISMATCH` line error | ✅ `lineWithTooFewValues_isReported` |
| 15 | Percentage 0, negative, > 100; 100 exactly | ]0, 100] - 100 accepted, 0 refused | ✅ `OfferTermsTest` |
| 16 | More than 2 decimals | `DISCOUNT_VALUE_TOO_PRECISE` (column is NUMERIC(12,2)) | ✅ `moreThanTwoDecimals_isRefused` |
| 17 | Fixed amount without currency / invalid currency / lower-case currency | Required, ISO 4217 checked, normalised to upper case | ✅ `fixedAmount_requiresAValidIsoCurrency` |
| 18 | Percentage with a currency | Line error `CURRENCY_NOT_ALLOWED` | ✅ `percentage_mustNotHaveACurrency` |
| 19 | `valid_to` before `valid_from`; same day | Refused; same day allowed | ✅ `validToBeforeValidFrom_isRefused_butSameDayIsAllowed` |
| 20 | `publish=true` on an already-expired offer | Line error `OFFER_EXPIRED` | ✅ `publishingAnExpiredOffer_isRefused` |
| 21 | Same reference twice in one file | First occurrence wins; later lines reported with both line numbers | ✅ `duplicateReferenceInFile_firstOccurrenceWins` |
| 22 | Reference differing only by case / spaces (`ABC` vs ` abc `) | Same offer (case-insensitive, trimmed) - assumption | ✅ `knownReferencesInNewFile_updateDrafts_unchangedLinesSkipped` |
| 23 | **Same file sent twice** | SHA-256 per supplier → same job returned (200, `alreadySubmitted: true`), nothing re-imported | ✅ `sameFileTwice_noDuplicates` |
| 24 | Same file uploaded twice **at the same moment** | Unique constraint `(supplier_id, file_sha256)` + `ON CONFLICT DO NOTHING` | 🧩 constraint `uq_import_job_file` |
| 25 | Same reference in a **new** file, offer still DRAFT | Updated | ✅ |
| 26 | Same reference, identical content (incl. `20` vs `20.00`) | `UNCHANGED`, nothing written (re-sending a catalogue is normal) | ✅ |
| 27 | Same reference, offer PUBLISHED/CLOSED, content different | Line error `OFFER_NOT_EDITABLE` (BR-08); other lines unaffected | ✅ `changingPublishedOffer_failsForThatLineOnly` |
| 28 | Same reference, offer DRAFT, identical content, `publish=true` | Published (counted as updated) | 🧩 `OfferImportWriter` |
| 29 | Offer present yesterday, missing from today's file | **Not deleted** (delta semantics) - a truncated export must never wipe a catalogue | 🧩 by design |
| 30 | Worker crashes in the middle of a file | Chunk rolled back; lease expires; another worker resumes after the checkpoint | 🧩 lease + `last_committed_row` |
| 31 | Chunk re-processed after a crash | Idempotent: upsert by reference, error rows unique per (job, line, field, code) | 🧩 `uq_import_error_once` |
| 32 | Worker lost its lease while another took over | Checkpoint update guarded by expected previous value → loser rolls back, no double counting | 🧩 `ImportJobRepository.recordChunk` |
| 33 | Technical failure 3 times | Job `FAILED` with a generic, actionable message + support reference; details only in logs; alert | 🧩 `ImportWorker` |
| 34 | Same file re-sent after a technical failure | Existing job re-queued, not duplicated | ✅ `resendingAfterTechnicalFailure_requeuesTheSameJob` |
| 35 | Two imports of the same supplier at once | One running import per supplier (claim filter + partial unique index) | 🧩 `uq_import_job_one_running_per_supplier` |
| 36 | Supplier edits a draft in the UI during the import | Guarded batch UPDATE touches 0 rows → chunk retried against fresh data | 🧩 `ConcurrentOfferChangeException` |
| 37 | Supplier imports for another supplier | Impossible: supplier comes from the identity, never from the file | ✅ |
| 38 | Malicious file name (`../../etc/passwd`) | Never used for paths; storage keys are UUIDs; path normalisation guard | 🧩 `ImportFileStorage` |
| 39 | **CSV injection** in the downloadable report (`=HYPERLINK(...)`) | Cells starting with `= + - @ \t \r` prefixed with `'` | ✅ `errorReportCsv_neutralisesFormulas`, `ErrorReportCsvTest` |
| 40 | Supplier reads an import of another supplier / marketplace | 404 | ✅ `supplierOfB_cannotReadImportOfA` |
| 41 | Huge error report | Keyset pagination (`afterId`), CSV streamed page by page | ✅ |
| 42 | Retention | Files and error rows purged after 30 days | 📐 |

### Bulk JSON API (R3 "or through an API")

| # | Edge case | Decision | Status / where |
|---|-----------|----------|----------------|
| 43 | 100,000 offers in one API call | Converted, while streaming, into the CSV format and processed by the **same** pipeline: same chunks, report, idempotency, recovery | ✅ `partialErrors_reportedByItemIndex` |
| 44 | Where is the bad offer? | `itemIndex` (0 = first item of `offers`), JSON field names (`discountValue`) | ✅ `partialErrors_reportedByItemIndex` |
| 45 | Same request sent twice | Same job returned (200, `alreadySubmitted: true`) | ✅ `sameRequestTwice_noDuplicates` |
| 46 | Not JSON, no `offers` array, items that are not objects, truncated body | 422 `IMPORT_FILE_UNREADABLE` with the reason, no job created | ✅ `wrongShape_isRefused` |
| 47 | Empty `offers` | 422 `IMPORT_FILE_EMPTY` | ✅ `wrongShape_isRefused` |
| 48 | Endless body, or a false `Content-Length` | Reading stops at 50 MB (`IMPORT_FILE_TOO_LARGE`) | 🧩 `SizeLimitedInputStream` |
| 49 | `12.345` sent as a JSON number | Read as an exact decimal, so it is refused (too precise) instead of being rounded | 🧩 `JsonOfferSource` |
| 50 | Unknown fields in an item | Ignored, so supplier systems can send extra data | 🧩 by design |
| 52 | "Will my file go through?" before importing | Dry run (`/api/imports/dry-run`): the same connector, checks and decisions (incl. BR-08 against stored offers), nothing stored | ✅ `dryRun_reportsWithoutChangingAnything`, `dryRun_reportsByItemIndex_andStoresNothing` |
| 53 | 5,000 errors: where to start? | `errorSummary`: problems grouped by code, most frequent first ("38 x DATE_INVALID") | ✅ `partialErrors_validLinesImported_invalidLinesReported` |
| 54 | A new input format (Excel matrix, GDSN) | One more `OfferSourceAdapter`; the rest of the pipeline is shared | 🧩 `CsvOfferSource`, `JsonOfferSource` |
| 51 | Same offers sent once as CSV and once as JSON | Not detected as the same: the two formats produce different stored files. Both imports are harmless anyway (upsert by reference) | 🧩 assumption |

## 2. Partnerships (BR-03 … BR-07, R6)

| # | Edge case | Decision | Status |
|---|-----------|----------|--------|
| 1 | End date equal to start date | Refused (end must be after start) | ✅ `PartnershipTest` |
| 2 | 36 months from Jan 31 / Feb 29 | `plusMonths(36)` in Java = `+ interval '36 months'` in SQL (month-end clamping) | ✅ parameterised test |
| 3 | Are start/end inclusive? | Both inclusive (assumption) | ✅ `statusIsDerivedFromTheDates` |
| 4 | "Today" at 00:30 Paris = 22:30 UTC the day before | Injected `Clock` in Europe/Paris; SQL never uses `CURRENT_DATE` | ✅ |
| 5 | Status correct without a nightly job | Derived from dates + `terminated_at` (BR-05) | ✅ |
| 6 | Adjacent partnerships (one ends the 31st, next starts the 31st) | Overlap (both include the 31st) → refused | 🧩 `[]` range in the exclusion constraint |
| 7 | **Two overlapping creations at the same moment** | Exclusion constraint guarantees one wins; translated to 409 | ✅ `concurrentOverlappingCreations_onlyOneSucceeds` |
| 8 | Terminated partnerships and overlap | Excluded from BR-04 (`WHERE terminated_at IS NULL`) | 🧩 |
| 9 | Terminate a SCHEDULED partnership | Allowed | ✅ |
| 10 | Terminate an EXPIRED / TERMINATED one | 422 `PARTNERSHIP_NOT_TERMINABLE` | ✅ |
| 11 | Renew on day 30 / day 31 after expiry | Day 30 allowed (starts today), day 31 refused | ✅ |
| 12 | Renew an ACTIVE partnership | Successor starts the day after the current end - no gap, no overlap | ✅ `activePartnership_isRenewed` |
| 13 | Renew twice | Second successor overlaps the first → refused by BR-04 | 🧩 |
| 14 | Termination cancels subscriptions (BR-07) | Same transaction, via a synchronous in-process event | ✅ `termination_cancelsSubscriptions_andRemovesAccess` |
| 14b | **Terminate a partnership that was already renewed** | The renewal waiting to start (SCHEDULED successor) is terminated in the same transaction and audited; otherwise access would come back on its start date (R6) | ✅ `termination_alsoTerminatesTheScheduledRenewal` |
| 15 | **Subscription at the same moment as termination** | Termination locks the partnership `FOR UPDATE`, subscribing `FOR SHARE` → serialised | 🧩 `ActivePartnerships.lockIfActive` |
| 16 | Catalogue access after termination | Removed instantly: visibility computed at read time (no cache, no copy) | ✅ |
| 17 | Partnership **expires** (not terminated): subscriptions? | Kept; visibility lost through BR-10. **Open question for product** | 📐 |
| 18 | Partnership between supplier of A and retailer of B | Impossible: composite foreign keys `(marketplace_id, id)` | 🧩 |

## 3. Offers and subscriptions (BR-08 … BR-11)

| # | Edge case | Decision | Status |
|---|-----------|----------|--------|
| 1 | Edit a PUBLISHED offer | 422 `OFFER_NOT_EDITABLE` | ✅ `editingPublishedOffer_isRefused` |
| 2 | Backwards transition (CLOSED → PUBLISHED), close a draft | Refused: state machine only moves forward | ✅ `lifecycleOnlyMovesForward` |
| 3 | Publish an expired offer | 422 `OFFER_EXPIRED`, and **no outbox event** | ✅ `publication_andOutboxEvent_areAtomic` |
| 4 | Two users edit the same draft | Optimistic locking: stale version → 409 `CONCURRENT_MODIFICATION` | ✅ `staleVersion_isRefused` |
| 5 | Money in floating point | Never: `BigDecimal` / `NUMERIC(12,2)`, compared by value | ✅ |
| 6 | Another supplier's offer by id | 404, never 403 (existence not revealed) | ✅ `otherSuppliersOffer_isNotFound` |
| 7 | **Double-click on "subscribe"** / network retry / two tabs | Partial unique index + `ON CONFLICT DO NOTHING`; 201 first, then 200 with the same subscription | ✅ `subscribingTwice_isIdempotent` |
| 8 | Subscribe to an offer not visible (no partnership, draft, out of window) | 404 | ✅ `subscribingToInvisibleOffer_isNotFound` |
| 9 | Re-subscribe after cancellation | Allowed: new ACTIVE row (index only covers ACTIVE) | 🧩 |
| 10 | Offer visible only inside its window (BR-10) | `today BETWEEN valid_from AND valid_to` in the catalogue | 🧩 |

## 4. Marketplace isolation (R1)

| # | Edge case | Decision | Status |
|---|-----------|----------|--------|
| 1 | Query that forgets `WHERE marketplace_id` | Row-Level Security still filters | ✅ `databaseHidesOtherMarketplaces_evenWithoutWhereClause` |
| 2 | No marketplace in context | **Fail closed**: empty value → no rows | ✅ `withoutMarketplaceContext_databaseReturnsNothing` |
| 3 | Writing a row for another marketplace | Rejected by RLS `WITH CHECK` | ✅ `writingIntoAnotherMarketplace_isRejectedByTheDatabase` |
| 4 | Pooled connection keeps the previous marketplace | Value rewritten on **every** checkout | 🧩 `TenantAwareDataSource` |
| 5 | `SET` cannot take bind parameters (temptation to concatenate) | `set_config('app.marketplace_id', ?, false)` | 🧩 |
| 6 | RLS silently bypassed for table owners | Application uses a non-owner role `portal_app` | 🧩 migration V3 |
| 6b | **Application configured with the wrong database user** (the owner, a superuser, a `BYPASSRLS` role) | Start-up check: the application refuses to start, before serving any request or starting any worker. It also refuses if no table has RLS enabled | ✅ `DatabaseRoleGuardIT`, `DatabaseRoleGuardTest` |
| 7 | Background workers need work from all marketplaces | Only via `SECURITY DEFINER` claim functions returning ids; then `TenantContext.runAs` | 🧩 migration V4 |
| 8 | Thread pools leaking context | `TenantContext.callAs` restores in `finally`; interceptor clears in `afterCompletion` | 🧩 |
| 9 | Caller claims another marketplace id | Sees nothing of its own data, nothing of others | ✅ `retailerUsingAnotherMarketplaceId_seesNothing` |
| 10 | Guessable ids | UUIDs | 🧩 |
| 11 | Same company name in two marketplaces | Allowed; unique only within a marketplace (BR-01) | ✅ `supplierNameUniquePerMarketplace_caseInsensitive` |
| 12 | Customer requiring separate storage | Dedicated cell (same image, own DB/storage/keys) | 📐 |
| 13 | "Separately" means another country | Deploy that cell in the required region; backups, files, logs included | 📐 |

## 5. Notifications (R5)

| # | Edge case | Decision | Status |
|---|-----------|----------|--------|
| 1 | Offer published but notification lost (crash) | Transactional outbox: event committed with the offer | ✅ `publication_andOutboxEvent_areAtomic` |
| 2 | Only partners concerned are informed | ACTIVE partnerships only (not terminated, not scheduled, not other marketplaces) | ✅ `onlyActivePartnersAreNotified` |
| 3 | **Black Friday**: 500,000 offers × ~900 retailers | Coalescing: ≤ 1 pending notification per (retailer, channel); one event per import chunk | ✅ `manyPublications_coalesceIntoOneDigest` |
| 4 | 100 offers published seconds apart | Same coalescing - one digest | ✅ |
| 5 | Partnership terminated between publish and send | Content computed at send time → nothing sent (`SKIPPED`) | ✅ `partnershipTerminatedBeforeSending_nothingSent` |
| 6 | Two instances process the same event / notification | `FOR UPDATE SKIP LOCKED` + leases | 🧩 migration V4 |
| 7 | Event replayed (at-least-once) | Handlers idempotent (`ON CONFLICT DO NOTHING`) | 🧩 |
| 8 | Webhook endpoint down (5xx / timeout) | Retries with exponential back-off, then `FAILED` + alert | ✅ `failingWebhook_retriedThenFailed` |
| 9 | Webhook answers 4xx | Permanent failure, no retry | ✅ `rejectedWebhook_failsWithoutRetry` |
| 10 | Failed delivery loses offers? | No: cursor moves only after success; next digest starts from the same cursor | ✅ |
| 11 | New offers while a notification is being sent | Status `SENDING` is outside the pending unique index → a new pending row is created | 🧩 |
| 12 | Failing notification while a newer one is pending | Marked `SUPERSEDED` (the newer one carries the offers) | 🧩 |
| 13 | Retailer receives the same webhook twice | `X-Portal-Delivery-Id` for de-duplication | ✅ |
| 14 | Forged webhook | HMAC-SHA256 over `timestamp.body`; timestamp prevents replay | ✅ `webhook_isDeliveredAndSigned`, `WebhookSecurityTest` |
| 15 | **SSRF**: webhook URL to `localhost`, `10.x`, `169.254.169.254` | Refused (HTTPS only, public addresses only); redirects not followed | ✅ `dangerousTargets_areRefused` |
| 16 | Slow retailer or mail server | 2 s connect / 5 s read timeouts on webhooks and SMTP; delivery outside any DB transaction | 🧩 |
| 16b | **Slow deliveries use up a worker's lease** (another worker would claim the same batch and send it twice) | No delivery starts with under 30 s left on the 2-minute lease; the rest of the batch is released without using an attempt, or `SUPERSEDED` if a newer one is pending | ✅ `NotificationLeaseIT` |
| 17 | Offer whose transaction commits late | Window ends at `now - lag` (10 s); a notification is never due before the lag. This reduces the risk, it does not remove it: a commit later than the lag can still be missed (section 9) | 🧩 |
| 18 | Retailer without e-mail / webhook | Channel not created; if removed later → `SKIPPED` | 🧩 |
| 16c | Webhook answers with a redirect | Redirects are not followed, so a 3xx means "not delivered": `FAILED`, cursor not moved | ✅ `webhookRedirect_isNotASuccess` |
| 16d | Offer published with a future `valid_from` | Not in digests until valid (BR-10). Known gap: no second notification when it becomes valid (see section 9) | ✅ `offerNotYetValid_isNotInTheDigest` |
| 19 | Per-endpoint circuit breaker, retailer preferences | Production | 📐 |
| 20 | Outbox and notification tables grow forever | Purge / time partitioning of processed rows | 📐 |

## 6. API, errors, security

| # | Edge case | Decision | Status |
|---|-----------|----------|--------|
| 1 | Missing / invalid identity | 401 Problem Details | ✅ `missingIdentity_isRejected_withCorrelationId` |
| 2 | Wrong role | 403 `ROLE_NOT_ALLOWED` | ✅ |
| 3 | Malformed JSON, wrong type, unknown route | Same Problem Details shape (`MALFORMED_REQUEST`, `RESOURCE_NOT_FOUND`) | ✅ `malformedJson_isRejected` |
| 4 | Business rule broken | 422 with `businessRule` and **all** violated fields | ✅ `invalidOffer_reportsAllViolations` |
| 5 | Race slipping past Java checks | DB constraint name translated to the same business error (409, not 500) | 🧩 `DatabaseConstraintTranslator` |
| 6 | Unexpected exception | 500, generic message + correlation id; stack trace only in logs | 🧩 |
| 7 | Log injection via `X-Correlation-Id` | Validated (`[A-Za-z0-9._-]{1,64}`), otherwise regenerated | 🧩 `RequestTracingFilter` |
| 8 | Personal data in logs | `RetailerContact.toString()` hides it; messages never contain contact data | 🧩 |
| 9 | SQL injection | Parameterised SQL everywhere; dynamic catalogue filters built from fixed fragments | 🧩 |
| 10 | `%` / `_` typed in a search | LIKE wildcards escaped | ✅ `searchTextWildcardsAreEscaped` |
| 11 | Tampered pagination cursor | 400 `INVALID_PAGE_CURSOR`, never 500 | ✅ `CatalogueCursorTest` |
| 12 | Page size abuse | Bounded (catalogue ≤ 200, errors ≤ 500) | 🧩 |
| 13 | Database privileges | App role: no DDL, no DELETE; migrations run as owner | 🧩 migration V3 |
| 14 | Secrets | From environment / vault, no default in `application.yml`; compose values are local-only | 🧩 |
| 14b | Webhook secrets in the database | Encrypted with AES-256-GCM, key outside the database, bound to the retailer's id. Hashing is impossible: signing needs the original value | ✅ `WebhookSecretCipherTest` |
| 14c | SSRF through IPv6 private, carrier-grade NAT or reserved addresses, or credentials in the URL | Refused | ✅ `dangerousTargets_areRefused` |
| 14d | Huge number such as `1E+999999999` | Line error; never expanded into a billion characters | ✅ `hugeExponentInDiscount_isALineError` |
| 14e | NUL or control characters in text | Line error or 422; stored error reports remove NUL | ✅ `nulCharacterInCell_isALineError`, `nulCharacterThroughTheApi_isRefused` |
| 14f | Very long CSV cell | Reported; only 500 characters kept in the error report | ✅ `veryLongCell_isShortenedInTheReport` |
| 14g | Malformed multipart upload | 400, not 500 | 🧩 |
| 14h | Huge JSON body on a normal endpoint (memory exhaustion) | Bodies limited to 1 MB, counted while reading (a false Content-Length does not help); 413 `REQUEST_TOO_LARGE` | ✅ `oversizedRequestBody_isRefused` |
| 15 | OIDC, MFA, GDPR export/anonymisation, rate limiting | Production | 📐 |
| 16 | **Who changed what and when (R7)** | `audit_event` row in the same transaction as each change: author, action, before/after, correlation id | ✅ `offerChanges_areRecorded` |
| 17 | A refused or rolled-back change | No audit row: change and audit commit together or not at all | ✅ `refusedChange_isNotRecorded` |
| 18 | Someone tries to rewrite the history | The application role has no UPDATE or DELETE on `audit_event` | ✅ `trail_isAppendOnly` |
| 19 | 100,000 imported offers = 100,000 audit rows? | No: one row per chunk; each offer keeps `source_import_id`, which leads to it | ✅ `imports_areRecorded` |
| 20 | Audit read by the wrong person | Administrators only, their own marketplace only (RLS) | ✅ `trail_isVisibleToAdministratorsOfTheMarketplaceOnly` |

---

## 7. Performance decisions

| Area | Decision | Why |
|------|----------|-----|
| Imports | Streaming parser; constant memory | 100,000 lines never loaded at once |
| Imports | SHA-256 computed while storing | One read of the file |
| Imports | 1,000 lines per transaction | Short locks, bounded lost work, frequent checkpoint |
| Imports | One `SELECT … IN (:keys)` per chunk, then batch INSERT / batch UPDATE | ~4 round-trips per 1,000 lines instead of 2,000+ |
| Imports | `reWriteBatchedInserts=true` | Driver turns a batch into multi-row INSERTs |
| Imports | JDBC, not JPA, for bulk | No per-entity overhead, no persistence-context growth |
| Imports | Structure checked before accepting | No wasted background work on broken files |
| Imports | Unchanged lines not written | Re-sending a catalogue costs reads only |
| Catalogue | Partial index `(supplier_id, published_at DESC, id DESC) WHERE status='PUBLISHED'` | Smaller index, matches the sort |
| Catalogue | Partial index on live partnerships by retailer | Starts from ~15 rows |
| Catalogue | `CROSS JOIN LATERAL … LIMIT` per partner, then merge and limit | Each partner contributes at most one page, read newest-first from the index: the work is bounded by partners × page size (~15 × 50 rows), however large a supplier's catalogue grows. `CataloguePaginationIT` pages 2 by 2 across two partners with alternating publication times and checks the full order: nothing skipped, nothing repeated |
| Catalogue | Keyset pagination | Page 200 costs as much as page 1 |
| Catalogue | DTO projection, bounded page size | Less I/O, predictable latency |
| Catalogue | Read replica in production; partnership check on primary | Isolation from write load without breaking R6 |
| Catalogue | No cache at first | A cache would show offers after termination |
| Notifications | One event per chunk, not per offer | 500 events instead of 500,000 |
| Notifications | Set-based fan-out (`INSERT … SELECT`) | ~900 retailers in one statement |
| Notifications | Coalescing, content computed at send time | ≤ 300,000 × 2 rows instead of ~450 million |
| Notifications | Delivery outside transactions | No connection or lock held while waiting on SMTP/HTTP |
| Workers | `FOR UPDATE SKIP LOCKED` + leases | Horizontal scaling without double processing |
| Workers | Separate worker instances in production | Background load never competes with the catalogue |
| Tenancy | One `set_config` per connection checkout | Tiny cost for a hard isolation guarantee |
| Not now | Partitioning, sharding, Elasticsearch, Kafka, cache | Not needed at 3M offers; triggers documented |

## 8. Best-practice decisions (code and architecture)

- **Modular monolith**, one package per business module, no dependency cycles (in-process event to break partnership → subscription).
- **Three layers in every module**: controller → service → repository, enforced by `ArchitectureTest`. SQL exists only in repositories.
- **Column limits**: `VARCHAR(n)` on free text. PostgreSQL treats `TEXT` and `VARCHAR(n)` the same for speed; the limit protects the database and documents the column, and raising it later is cheap.
- **Business rules in domain objects** (`Partnership`, `Offer`, `OfferTerms`), unit-tested without Spring or a database.
- **One validation path** for the JSON API and the CSV import (`OfferTerms.violations`), reporting every problem at once with the caller's field names.
- **Database guarantees for race-prone rules** (unique, exclusion, check, composite foreign keys); Java checks for friendly messages.
- **Transactional outbox** with `Propagation.MANDATORY` - writing an event outside a transaction fails immediately.
- **Single error type** (`PortalException` + `ErrorCode` catalogue), RFC 9457 Problem Details, stable codes, business rule ids, correlation id in body, header and every log line.
- **Middleware pipeline**: tracing filter → identity interceptor → controller → global exception handler.
- **Thin controllers**, DTOs at the boundary, JPA entities never exposed; `open-in-view` disabled.
- **Time**: one injected `Clock` (Europe/Paris); `LocalDate` for business dates, `Instant`/`TIMESTAMPTZ` for moments; "today" passed to SQL.
- **Flyway** owns the schema (`ddl-auto: none`); explicit constraint names; least-privilege roles.
- **Idempotency everywhere it matters**: file hash, upsert by reference, unique error rows, subscribe, outbox handlers, webhook delivery id.
- **Bounded retries** with exponential back-off; permanent vs transient failures distinguished.
- **Tests against real PostgreSQL** (Testcontainers), as the application's non-owner role; workers called directly (no sleeps); unit tests for every business rule boundary.
- **Operational readiness**: graceful shutdown, health/readiness probes, Prometheus metrics (`portal.imports.lines`, `portal.notifications`), structured log pattern with correlation id and marketplace.
- **Configuration over code**: digest windows, chunk size, retries, timeouts - all externalised.

## 9. Known limitations and concerns

Open points from the code review, most important first. None blocks the proof of concept; each one needs a decision before production.

| # | Concern | Why it matters | What to do |
|---|---------|----------------|------------|
| 1 | **Identity comes from headers** | Anyone who can reach the API can claim any role or marketplace. Acceptable only in this PoC | OAuth2/OIDC tokens; only `ActorContextInterceptor` changes |
| 2 | **Audit trail: who is "who"** | Built (`audit_event`), but with header identity the recorded author is only as trustworthy as the headers. It also lives in the main database | Real login (concern 1); copy the trail to write-once storage |
| 3 | **Bulk API without an `Idempotency-Key` header** | Duplicates are detected from the content (same offers = same job), which covers the "sent twice by mistake" case. A client cannot yet name its own retries | Accept an `Idempotency-Key` header as well |
| 4 | **Offers published with a future `valid_from` are never announced** | BR-10 forbids showing them early, and the digest cursor then moves past them | A daily job that announces offers whose validity starts today |
| 5 | **The dedicated-storage marketplace (R1)** | The PoC shows one shared database; the contractual case is design only | Per-marketplace database routing, as described in the design document |
| 6 | **Sending is sequential** | One worker sends one notification at a time, about 10 per second with a fast receiver. Black Friday's peak can need about 170 per second | Send in parallel; more worker instances |
| 7 | **`/actuator/prometheus` is public** | Metrics reveal activity levels | A separate management port, or authentication |
| 8 | **No rate limiting** | One caller can saturate the API | API gateway limits per marketplace and caller |
| 9 | **`GET /api/subscriptions` has no pagination** | A retailer with many subscriptions gets one large response | Keyset pagination, as in the catalogue |
| 10 | **Stored files and queue tables are never purged** | Disk and table bloat | Scheduled retention job with its own database role |
| 11 | **Files are on local disk** | Several instances would not see each other's uploads | Object storage; only `ImportFileStorage` changes |
| 12 | **DNS rebinding** | A webhook host can change address between our check and the call | Pin the resolved address, or use an egress proxy |
| 13 | **Application clocks decide leases** | Instances with very different clocks could misjudge a lease | NTP on all instances; the safety margin absorbs small drift |
| 14 | **Encryption key rotation** | Stored values carry a format version, but no re-encryption job exists | Add a key id per value and a rotation job |
| 15 | **The `portal_app` password is set by a migration** | It passes through Flyway and could reach database logs | Create roles with infrastructure tooling in production |
| 16 | **A chunk the database rejects stops the rest of the file** | Lines are checked in Java with the same rules as the database, so this should not happen. But if the database refuses a line Java accepted (rules drifting apart), the whole 1,000-line chunk rolls back, is retried 3 times, and the job ends `FAILED`: later lines are never imported. That goes against R3's "errors on some lines never block the others" | When a chunk fails, replay it line by line with savepoints and record the database error as a line error |
| 17 | **A commit later than the consistency lag can miss a digest** | `published_at` is set before the import chunk commits. If the commit lands more than 10 s later (lock waits at peak), a digest may already have moved the retailer's cursor past that moment, and those offers are never announced | Alert when a chunk takes close to the lag; for a full fix, store the transaction id on each offer and move the cursor only up to the oldest transaction still running (`pg_snapshot_xmin`) |
| 18 | **One import at a time per instance** | Each worker type has one scheduler thread, so a 100,000-line import makes other suppliers' imports wait on that instance | More worker instances (already the production plan), or several import threads per instance |
| 19 | **Expired offers stay `PUBLISHED`** | Nothing closes them, so they stay in the catalogue's partial index. The `LATERAL` query reads newest first and stops after one page, so this costs little, but the index keeps growing | A daily job that closes expired offers |
| 20 | **Catalogue speed is designed, not measured** | No benchmark yet with production-like volumes (3M offers) to support the p95 < 200 ms target | A load test with generated data and `EXPLAIN ANALYZE` of the catalogue query |
| 21 | **Subscriptions stay ACTIVE when an offer is closed** | BR-07 only covers termination; the closed offer disappears from the catalogue but the subscription row stays ACTIVE. **Open question for product** | If product agrees, cancel them in the same transaction as the closure (as for termination) |
| 22 | **Old CLOSED offers are kept forever** | The `offer` table only grows: each year adds the offers of every supplier, most of them closed long ago. Not a problem at 3M rows, but nothing ever removes them | A daily job, after the one that closes expired offers (#19): offers CLOSED for longer than the retention period (e.g. 24 months, to agree with legal and product) are moved to an archive table or archive storage, together with their subscriptions. The audit trail keeps only the offer id, so the history stays readable. Partition `offer` only if it nears ~50M rows |
