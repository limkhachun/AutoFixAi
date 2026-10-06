# Crypto Portfolio — local development

## Setup completed

- Project-local Temurin Java 21.0.12.1 and Maven 3.9.16 in the workspace's work/tools folder.
- Spring Boot 4.1.1 backend, Java 21, REST health endpoint, and Actuator.
- React + TypeScript frontend with Vite and a development API proxy.
- Node.js 24.21.0 reused from the computer.
- MySQL 8.4.11 runs on 127.0.0.1:3307. Flyway V1 creates users, assets, transactions, and cached prices.
- Dashboard and transaction prototypes support English/Mandarin and MYR display. Sample USD values are converted using an explicitly illustrative USD 1 = MYR 4.20 rate; this is not a live FX quote. Transaction inputs are native MYR.
- Registration, login, session restoration, account details, and logout now connect to MySQL through Spring Security. Private BUY/SELL transactions now persist, with editing, deletion, and undo.
- Flyway V4 records provider quote timestamps separately from fetch timestamps.
- Live MYR prices for BTC, ETH, and SOL are fetched through the backend and cached in MySQL. Quotes older than 10 minutes are labeled stale; missing quotes leave totals unavailable rather than treating them as zero.
- Flyway V3 adds soft deletion for transaction recovery.
- Flyway V2 renames monetary columns to neutral names and records quote_currency explicitly. Existing USD records retain USD; new records default to MYR.

## Start the applications

Open three PowerShell terminals in this folder. First start MySQL with `./start-database.ps1`. Then start the backend and frontend in separate terminals as below.

Terminal 1:

```powershell
./start-backend.ps1
```

Terminal 2:

```powershell
./start-frontend.ps1
```

Open http://127.0.0.1:5173/. Explore Overview, Transactions, Sign in, and the language switch. All sample data is labeled. `/api/health` now verifies MySQL as well as the backend.

Stop each application with Ctrl+C in its terminal. These development processes run locally; restart them after closing the development session or rebooting.

## Build and check

Keep MySQL running and stop the backend before packaging: Windows locks its running JAR. Java tests use the local development database and clean up their disposable test records. Never run them against production.

```powershell
./build-backend.ps1
cd frontend
npm.cmd run build
npm.cmd run lint
node --test tests/currency.test.mjs
```

After backend source changes, stop the backend, rebuild it, and start it again. Frontend source changes update automatically while its development server runs.

The launch scripts use project-local Java and Maven. They do not depend on the system Java 8 installation. Keep the workspace's work/tools folder alongside outputs; copying only this project folder will require restoring those tools.

For a different computer: install Java 21, Node.js, and MySQL 8.4. Create an empty crypto_portfolio database and dedicated database account. Set DATABASE_URL, DATABASE_USER, and DATABASE_PASSWORD in the terminal environment, use the backend's Maven wrapper (`mvnw.cmd package` on Windows), then run the generated JAR. Flyway creates the schema. Run `npm.cmd ci` then `npm.cmd run dev` from frontend. The helper scripts here are tailored to this workspace.

Local credentials are stored outside the project in workspace `work/mysql-credentials.json`; database files are in `work/mysql-data`. Do not publish the work folder. The local database account has schema-management privileges for Flyway; production should separate migration and runtime accounts. Local JDBC disables TLS for loopback development; configure authenticated TLS for deployment.

## Verification performed

- Frontend production build: passed.
- Frontend lint: passed without warnings.
- Backend Maven package: passed; 7 Java tests passed without failures or skips.
- HTTP authentication integration checks: anonymous account access rejected, registration/logout without CSRF rejected, passwords hashed, duplicate registration rejected, incorrect password rejected, separate accounts isolated, logout invalidates only its own session.
- Currency migration V2 applied, and schema integration checks verify new transaction records default to MYR.
- Browser sign-in with a disposable account, session restoration after refresh, and logout passed; the temporary account was removed afterward.
- Flyway V1 applied and seeds exactly BTC, ETH, SOL.
- MySQL rejects negative transaction quantities and missing user references; test users and transactions were rolled back.
- Backend GET /api/health: HTTP 200 with status UP.
- Frontend proxy GET /api/health: HTTP 200 with status UP.
- Direct and proxied health responses include database UP.
- Transaction checks cover weighted-average cost, fees, realized profit, historical overselling, rollback, recovery, concurrent sales, per-account authorization, and CSRF.
- Browser: saving, editing, deletion, and undo update real holdings. The disposable test account was removed afterward.
- Two currency-formatting tests pass, including exact large decimal values and rounding.
- Screenshot reviewed at the browser's current narrow viewport; a dedicated 375-pixel check is pending.

## Setup issues recorded

Windows PowerShell and curl downloads failed with TLS credential errors. Verified HTTPS downloads through Node.js succeeded. The Spring initializer supplied a version ending in .RELEASE that was unavailable in Maven Central; corrected it to the verified published 4.1.1 artifact.

Initial Java compilation reported an AccessDeniedException while closing a dependency archive; Maven clean also encountered a file-access error. A later package run succeeded and the app ran correctly, but the underlying Windows file-access behavior is unresolved. If it recurs, preserve the error and investigate it rather than treating a failed build as successful.

Additional parent-folder read permissions did not resolve the intermittent Java path-resolution issue. Stopping the running backend resolved a separate JAR packaging lock.

## Next milestone

Next: responsive/mobile verification, remaining edge cases, Docker packaging, and deployment. MYR quotes come directly from the provider; a separate FX conversion is not required for these valuations.

## Account use

Choose Sign in, then Create account. Passwords must contain 12–64 characters and at most 72 UTF-8 bytes. Registration does not automatically log in; sign in afterward. Sessions use HttpOnly, SameSite=Lax cookies and a 30-minute timeout. CSRF protection remains enabled for registration, login, and logout. Use SESSION_COOKIE_SECURE=true when deploying over HTTPS.

Signed-in accounts show their own transactions, quantities, weighted-average cost, and realized profit in MYR. Fees are included. Market value and unrealized profit use CoinGecko MYR quotes. Signed-out visitors see labeled sample data. Overselling is rejected across the complete chronological history, including backdated edits. Historical USD records require explicit conversion before calculation; they are not relabeled as MYR.

## Official references

- Spring project generator: https://start.spring.io/
- Vite setup guide: https://vite.dev/guide/
- Eclipse Temurin: https://adoptium.net/


## Market-price configuration

The backend batches three CoinGecko IDs using /simple/price with vs_currencies=myr and provider timestamps. It shares a 2-minute refresh window across accounts, waits 1 minute after a failure, and times out a request after 6 seconds. The dashboard refreshes automatically every 2 minutes while signed in and offers a refresh button. Invalid or incomplete responses do not overwrite cached quotes. Stale cached values remain visible as estimates; unavailable held-asset prices suppress the full portfolio total.

An optional COINGECKO_DEMO_API_KEY environment variable adds the Demo API header on the server. Keep keys out of frontend code and source control. Keyless access worked in the local probe but may be rate-limited or blocked. Provider rules and availability can change. Prices are rounded to the database's 12 decimal places before storage; portfolio multiplication uses BigDecimal.

Official references: https://docs.coingecko.com/v3.0.1/reference/simple-price and https://docs.coingecko.com/docs/errors-and-rate-limits .


## Deployment preparation

Free demo hosting selected: Render Free for the combined Java/React Docker image and Aiven Free for MySQL. See deployment.md and render.yaml. No hosting resources have been created. Docker is absent locally, so the container image has not been built or tested. The 375-pixel dashboard check found and fixed clipped metric cards; frontend build/lint and all seven backend tests passed after the changes.

