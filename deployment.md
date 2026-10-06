# Free demo hosting: Render + Aiven

Selected for this portfolio demo: Render Free runs a single Docker image containing the Java API and built React UI. Aiven Free provides persistent MySQL. Both frontend and API use the same HTTPS origin, preserving the current session and CSRF workflow.

Nothing has been deployed or provisioned yet. Docker is not installed on this computer; the image build and hosted checks remain unverified. The Dockerfile builds the UI and backend inside Linux, runs frontend checks, and uses a non-root Java runtime. Run the backend integration tests separately with a disposable MySQL database before release; the image build deliberately has no database and skips backend tests.

## Free-tier limits checked on 6 October 2026

Render Free sleeps after 15 minutes without traffic; waking takes about a minute. Free hours, bandwidth, and build minutes have limits. High outbound traffic, including external database/API calls, can trigger suspension. Without a payment method, exceeding bandwidth/build allowances suspends services or builds instead of charging for that excess. Use the Free compute plan and monitor usage. Render Free is intended for demos, not production.

Aiven Free MySQL currently includes one node, 1 GB RAM, 1 GB disk, and backups, with no fixed expiry and no credit card required. It can be powered off for inactivity, and its terms/configuration can change. Keep the sample database small. Session state is currently held in the Java process, so restarts require signing in again; transactions persist in Aiven.

## Prepare accounts and source

1. Create accounts at https://dashboard.render.com/ and https://console.aiven.io/. Select free plans, not paid trials or upgrades.
2. Put this project folder in a Git repository with Dockerfile and render.yaml at its root. Do not include the workspace work folder, local database files, credentials, .env files, or secret files. Publishing source needs a destination repository chosen by you.
3. In Aiven, create a Free MySQL service. Check its MySQL version against Flyway support before deployment. Create a dedicated database (for example crypto_portfolio) and database user; grant privileges needed for migrations on that database only. Do not expose database credentials in chat or commit them.

## Configure Render

Create a Free Docker web service from the repository, or use the included Blueprint. The Blueprint creates only the app; it does not create or bill an Aiven service. Leave the Docker start command unset so the image entrypoint runs. Set /api/health as the health-check path.

Enter these secrets directly in Render's environment settings:

- DATABASE_URL: a JDBC URL, not Aiven's mysql:// URI. Use jdbc:mysql://HOST:PORT/DATABASE?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true . Replace HOST, PORT, and DATABASE with the Aiven values. Do not put the password in the URL.
- DATABASE_USER: the dedicated database username.
- DATABASE_PASSWORD: that user's password.
- COINGECKO_DEMO_API_KEY: optional provider key, entered only on the server.

The Blueprint sets secure session cookies and a small connection pool. Render supplies PORT; the app listens on it and on all container interfaces. Browser traffic must use Render's HTTPS URL.

MySQL certificate verification must succeed. If Aiven's service certificate chains to a private CA, import the CA certificate downloaded from that service into a Java truststore and supply the Connector/J truststore URL/type/password settings through Render environment variables. This truststore is a deployment-specific file; it has not been supplied or configured yet. Do not disable certificate verification to bypass a connection failure. Validate the certificate hostname as well as the chain.

## Hosted acceptance checks

After a successful image build and database migration, verify HTTPS health/database status, registration/login/logout, a saved purchase after refresh/redeployment, account isolation, overselling rejection, edits/deletion/undo, MYR values, quote timestamps, provider-failure messages, and phone-sized English/Mandarin layouts. Check logs and actual memory use: Java heap/metaspace limits are starting settings, not a guarantee the process fits the free instance. Adjust after observing a deployment.

## Official references

- https://render.com/docs/free
- https://render.com/docs/docker
- https://render.com/docs/blueprint-spec
- https://aiven.io/docs/products/mysql/concepts/mysql-free-tier
