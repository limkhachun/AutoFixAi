# Development journal — 6 October 2026

## AI-assisted UI design and database foundation

Goal: create reviewable dashboard, history/form, and sign-in designs and prepare MySQL-backed persistence.

Design workflow: create a code-native React prototype directly with ChatGPT/Codex. No Figma, v0, or other dedicated AI design tool was used. To demonstrate the job's broader design-tool requirement, refine a screen in one later and keep its export.

Accepted proposal: three screens, four core tables, and versioned SQL migrations. Emphasize portfolio value, separate realized/unrealized profit, label sample prices, and support English/Mandarin.

Correction from testing: in this JDBC configuration, MySQL CHECK violations surface as SQL error 3819 with UncategorizedSQLException. Assert that exact code and named constraint. Missing owners are checked separately through the foreign-key exception. Stop the running backend before packaging because Windows locks its JAR.

Verification: frontend build/lint passed; three Java tests passed; Flyway V1 applied; database health passed directly and through Vite; browser language switching, login navigation, and valid transaction preview worked. Test users and transactions were rolled back.

Limits: login and saving are prototypes, prices are fixtures, and application authorization is still pending. Java archive path resolution intermittently fails in the Windows sandbox. A dedicated 375-pixel layout check is pending.

Learning checkpoint: explain why money uses DECIMAL, why foreign keys do not enforce user authorization, why overselling needs application-level history validation, and why an applied migration should be extended with a new version rather than edited.

## MYR and authentication milestone

Request: display prices in MYR and continue implementation. The sample portfolio converts its original USD fixtures at an explicitly illustrative rate of 4.20. New transaction inputs use MYR. V2 adds explicit quote currencies and preserves any historical USD data.

Implemented real registration and session login/logout using Spring Security, BCrypt with cost 12, session-backed CSRF tokens, and private /api/auth/me responses. Passwords are validated for length and BCrypt's UTF-8 byte limit. The frontend fetches a fresh CSRF token before each state-changing auth request, restores an existing session on load, and does not store passwords or bearer tokens in browser storage.

Testing corrected a migration issue: MySQL refuses to rename columns referenced by CHECK constraints. V2 drops those constraints before renaming and recreates them against the new names. The original failed first statement left columns unchanged; its failed local migration marker was removed before rerunning the corrected migration. V1 was preserved.

Verification: all four Java tests passed; the auth test exercises real HTTP with two independent cookie stores, registration/login/logout, bad credentials, CSRF rejection, hashing, and account isolation. New transaction rows default to MYR. Frontend build/lint passed. Transaction saving and real portfolio calculations are still pending.

Browser verification: signed in with an existing disposable local test account, refreshed and confirmed the session persisted, then signed out. The disposable account was removed afterward. The MYR dashboard shows MYR16,590.00 in sample value and MYR1,612.80 in sample unrealized profit at the explicitly illustrative exchange rate.

## Transactions and holdings milestone

Implemented private BUY/SELL recording, editing, soft deletion, and undo. V3 adds deleted_at without changing applied migrations. Signed-in dashboards now display actual account holdings, weighted-average cost, fee-inclusive cost basis, and realized profit in MYR. Market values remain unavailable until market prices are connected.

Each mutation locks the account row, replays its complete chronological history using BigDecimal, and rejects overselling atomically. This covers backdated edits and concurrent sales. Authorization derives the owner from the authenticated session. Historical USD records are rejected for calculation rather than silently treated as MYR. A single read endpoint returns history and summary from one transaction snapshot. Frontend monetary strings are formatted with integer arithmetic to retain precision.

Verification: all six backend tests passed with no failures or skips; two frontend currency tests passed; frontend production build and lint passed. Real HTTP tests cover account isolation, CSRF, cost calculations, full exits, invalid input, rollback, recovery, and concurrent overselling. Browser verification created a purchase, edited its quantity from 1 to 2 (MYR cost basis 102 to 202), deleted it, and restored it. The disposable browser account and its records were removed after logout.

The intermittent Windows Java archive/path-access issue remains unresolved; the final full package and test run succeeded. No compiler workaround is retained in the project.

Learning checkpoint: explain why concurrent sales need a shared account lock, why a backdated change must validate later trades, how fees affect average cost and realized profit, and why binary floating-point should not calculate monetary values.

## Market-price and valuation milestone

Added backend CoinGecko MYR quote integration for BTC, ETH, and SOL, with a shared two-minute refresh window and one-minute failure cooldown. V4 stores the upstream quote timestamp separately from the local fetch time. Complete responses are validated before atomically updating the MySQL cache. Quotes older than ten minutes are labeled stale; incomplete or malformed responses preserve the previous cache.

Valuation multiplies actual quantities by native MYR quotes using BigDecimal and subtracts remaining fee-inclusive cost basis for unrealized profit. A missing quote for a held asset leaves total valuation unavailable; an empty portfolio still has zero value. The UI shows source timestamps, cached-price warnings, provider attribution, manual refresh, and automatic refresh. Anonymous sample values remain clearly illustrative.

Verification: seven backend tests passed without failures or skips, plus both currency-formatting tests, frontend build, and lint. The new real HTTP test uses a local provider stub to verify exact decimal valuation, cache reuse, stale quotes, 429 cooldown, incomplete-response rollback, missing prices, and empty holdings. Test cache contents are restored after the test. The Windows compiler archive-close error recurred; the final full package succeeded without changing the project's standard build configuration.

Learning checkpoint: explain why fetched_at and source_updated_at differ, why missing prices must not become zero, why provider calls belong on the server, and why stale estimates need visible labels.

Live browser verification: a disposable portfolio holding 0.01 BTC with MYR3,005 cost showed MYR3,509.95 market value and MYR504.95 unrealized profit. Provider source timestamps were visible; manual refresh and Mandarin labels worked. The account was signed out and removed with its test transaction. Saved market-preview.png records this point-in-time result.

## Mobile layout and free-hosting preparation

At 375 pixels, the original two-column metric grid clipped cards. Small screens now use a single column for metrics and forms, 44-pixel button targets, 16-pixel inputs, wrapping actions, and keyboard-focusable scroll regions with localized table hints. The verified dashboard root and cards fit within the viewport without horizontal page overflow. Full signed-in phone workflows remain to be checked.

Selected Render Free plus Aiven Free MySQL after checking official limits. Prepared a multi-stage Dockerfile that packages React assets inside Spring Boot, a Render Free Blueprint, and deployment instructions. Static GET routes are public while APIs retain authorization/CSRF. Container binding and port are configurable, and HTTPS deployment sets secure cookies. Seven backend tests passed; frontend build/lint passed. Docker image build, hosted MySQL certificate configuration, account setup, source publication, and actual deployment remain pending.

