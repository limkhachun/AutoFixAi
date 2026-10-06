$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$env:npm_config_cache = Join-Path $workspaceRoot 'work/npm-cache'
Push-Location (Join-Path $PSScriptRoot 'frontend')
try { npm.cmd run dev } finally { Pop-Location }
