$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/load-database-env.ps1"
$projectRoot = $PSScriptRoot
$workspaceRoot = Split-Path (Split-Path $projectRoot -Parent) -Parent
$toolsRoot = Join-Path $workspaceRoot 'work/tools'
$jdk = Get-ChildItem $toolsRoot -Directory -Filter 'jdk-*' | Select-Object -First 1
if (!$jdk) { throw 'Project-local JDK missing. Restore work/tools or set up Java 21.' }
$env:JAVA_HOME = $jdk.FullName
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$jar = Join-Path $projectRoot 'backend/target/portfolio-0.0.1-SNAPSHOT.jar'
if (!(Test-Path $jar)) { throw 'Build backend first with build-backend.ps1.' }
& "$env:JAVA_HOME/bin/java.exe" -jar $jar
