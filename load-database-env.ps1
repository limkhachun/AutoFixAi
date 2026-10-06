$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$credentialsFile = Join-Path $workspaceRoot 'work/mysql-credentials.json'
if (!(Test-Path $credentialsFile)) { throw 'Local database credentials missing. See README.md.' }
$credentials = Get-Content $credentialsFile -Raw | ConvertFrom-Json
$env:DATABASE_URL = 'jdbc:mysql://127.0.0.1:3307/crypto_portfolio?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:DATABASE_USER = 'portfolio_app'
$env:DATABASE_PASSWORD = $credentials.appPassword
