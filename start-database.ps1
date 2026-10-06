$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$mysqlRoot = (Get-ChildItem "$workspaceRoot/work/tools" -Directory -Filter 'mysql-*' | Select-Object -First 1).FullName
if (!$mysqlRoot) { throw 'Project-local MySQL missing from work/tools.' }
$dataRoot = Join-Path $workspaceRoot 'work/mysql-data'
if (!(Test-Path $dataRoot)) { throw 'Database has not been initialized.' }
& "$mysqlRoot/bin/mysqld.exe" "--basedir=$mysqlRoot" "--datadir=$dataRoot" --bind-address=127.0.0.1 --port=3307 --mysqlx=OFF --console
