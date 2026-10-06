$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/load-database-env.ps1"
$projectRoot = $PSScriptRoot
$workspaceRoot = Split-Path (Split-Path $projectRoot -Parent) -Parent
$toolsRoot = Join-Path $workspaceRoot 'work/tools'
$jdk = Get-ChildItem $toolsRoot -Directory -Filter 'jdk-*' | Select-Object -First 1
$maven = Get-ChildItem $toolsRoot -Directory -Filter 'apache-maven-*' | Select-Object -First 1
if (!$jdk -or !$maven) { throw 'Project-local Java/Maven tools missing from work/tools.' }
$env:JAVA_HOME = $jdk.FullName
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
& "$($maven.FullName)/bin/mvn.cmd" -f "$projectRoot/backend/pom.xml" "-Dmaven.repo.local=$workspaceRoot/work/maven-repository" package -B
if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
