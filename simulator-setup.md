# Simulated MYR trading and admin funding

This simulator uses virtual MYR, not deposits or exchange orders. Manual portfolio records remain independent. Each account starts with zero simulated cash and no simulated crypto.

## Enable your admin account

1. On the existing live website, register `admin@user.com` with your own strong password and verify that you can sign in. Do this **before** setting the admin environment variable: registration of the configured admin email is blocked to prevent someone else claiming it.
2. Push the new application commit to GitHub.
3. In Render → crypto-portfolio → Environment, add `SIMULATION_ADMIN_EMAIL` with value `admin@user.com`. Save and redeploy. Do not put an admin password in an environment variable or chat.
4. After deployment succeeds, sign in as that account and select **Simulator**. The **Admin · Funding requests** panel is visible only to the designated admin; the server independently checks every approval.

Leaving the variable blank disables admin privileges. Changing it transfers this simulator role to the existing account with the new email; approved funding and trades remain in the database. The admin email must name an account you control.

## Try the flow

1. Sign in as a normal user → Simulator → request MYR 10,000.
2. Sign in as the admin in a separate browser session → Simulator → refresh → approve that request.
3. Refresh the user's Simulator. Available simulated cash becomes MYR 10,000.
4. Choose BTC, ETH, or SOL and a quantity, then execute a simulated buy. The server selects a recent CoinGecko MYR quote, debits cash and records the crypto quantity atomically.
5. Sell some owned crypto. Cash is credited and crypto quantity decreases. Refresh or sign in again to verify persistence.

One pending request per account is allowed, between MYR 0.01 and MYR 1,000,000. Approved requests credit their exact amount once; rejected requests credit nothing. Users cannot approve requests or read other users' requests/wallets.

Trades have zero fees and settle to MYR cents. Buys round cost upward and sells round proceeds downward, preventing cash creation through rounding. Trades with zero-cent proceeds are rejected. Simulated trades are final, with no edit/delete/undo endpoint. Quotes must have both provider and fetch timestamps within 10 minutes. Insufficient cash, overselling and stale/missing quotes reject the entire trade. Concurrent transactions share the account row lock.

Wallet balances are derived from immutable trades and approved requests. The latest 100 trades and personal requests appear in the UI; admins see up to 100 requests with pending requests prioritized. Previously approved cash and all trades still count in balance calculations.

## Local verification

Frontend build, lint and both exact-currency tests passed. Local HTTP/MySQL checks passed for authentication, CSRF, input validation, admin-only review, private wallets/requests, one pending request, double approval, concurrent spending, buy/sell settlement, overselling, rejected funding, admin-email reservation and stale-price rejection without wallet changes. Desktop English and phone-sized Mandarin screens were checked. These tests used a local price fixture, not live prices or real money; test accounts, funding, trades and fixture quotes were removed afterward.

A repeatable Spring/MySQL integration test is included in `SimulationIntegrationTests.java`. The full Maven test run was blocked on this Windows machine by the previously documented Java compiler archive/file-access issue; HTTP checks verified the running packaged backend instead. Run `build-backend.ps1` (or Maven `test` with a configured local MySQL database) on a working Java environment to run the full suite. The container build does not run database tests.

The live deployment and admin setup must still be completed by the account owner. The migration adds funding_request and simulated_trade tables without rewriting previous migrations or manual transaction records.
