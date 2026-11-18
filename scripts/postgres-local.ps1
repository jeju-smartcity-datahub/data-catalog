[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'status')][string]$Action = 'start',
    [switch]$Test
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$taskEnvFile = Join-Path $taskRoot '.env.dev'
if (-not (Test-Path -LiteralPath $taskEnvFile)) { throw 'Run npm run env:init first.' }
$taskConfig = @{}
foreach ($taskLine in Get-Content -LiteralPath $taskEnvFile) {
    if ($taskLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $taskConfig[$Matches[1]] = $Matches[2].Trim('"', "'") }
}
$taskBin = Join-Path $taskRoot '.tools/postgresql-17.11/pgsql/bin'
if (-not (Test-Path -LiteralPath (Join-Path $taskBin 'pg_ctl.exe'))) { throw 'Run npm run setup:postgres first.' }
$taskName = if ($Test) { 'postgres-test' } else { 'postgres' }
$taskRuntime = [IO.Path]::GetFullPath((Join-Path $taskRoot ".runtime/$taskName"))
if (-not $taskRuntime.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Runtime path escaped workspace.' }
$taskData = Join-Path $taskRuntime 'data'
$taskPort = if ($Test) { $taskConfig.POSTGRES_TEST_PORT } else { $taskConfig.POSTGRES_PORT }
$taskDatabase = if ($Test) { 'datacatalog_test' } else { $taskConfig.POSTGRES_DB }
$taskUser = $taskConfig.POSTGRES_USER
if ($taskPort -notmatch '^\d{4,5}$' -or [int]$taskPort -gt 65535) { throw 'Invalid PostgreSQL port.' }
if ($taskUser -notmatch '^[a-z][a-z0-9_]*$' -or $taskDatabase -notmatch '^[a-z][a-z0-9_]*$') { throw 'Use simple lowercase database/user names.' }
if ([string]::IsNullOrWhiteSpace($taskConfig.POSTGRES_PASSWORD)) { throw 'POSTGRES_PASSWORD is required.' }
$taskCtl = Join-Path $taskBin 'pg_ctl.exe'
if ($Action -eq 'stop') {
    if (-not (Test-Path -LiteralPath (Join-Path $taskData 'postmaster.pid'))) { Write-Output 'Cluster is already stopped.'; exit 0 }
    & $taskCtl -D $taskData -m fast -w stop
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL stop failed.' }
    exit 0
}
if ($Action -eq 'status') { & $taskCtl -D $taskData status; exit $LASTEXITCODE }
New-Item -ItemType Directory -Path $taskRuntime -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $taskData 'PG_VERSION'))) {
    $taskPwFile = Join-Path $taskRuntime 'init-password.txt'
    try {
        [IO.File]::WriteAllText($taskPwFile, $taskConfig.POSTGRES_PASSWORD)
        & (Join-Path $taskBin 'initdb.exe') -D $taskData -U $taskUser --encoding=UTF8 --locale=C --auth-host=scram-sha-256 --auth-local=scram-sha-256 "--pwfile=$taskPwFile"
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL initialization failed.' }
    } finally { if (Test-Path -LiteralPath $taskPwFile) { Remove-Item -LiteralPath $taskPwFile } }
    Add-Content -LiteralPath (Join-Path $taskData 'postgresql.conf') -Value "`nlisten_addresses = '127.0.0.1'`nport = $taskPort`npassword_encryption = 'scram-sha-256'"
}
& $taskCtl -D $taskData status *> $null
if ($LASTEXITCODE -ne 0) {
    & $taskCtl -D $taskData -l (Join-Path $taskRuntime 'postgres.log') -w start
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL start failed; inspect .runtime log (check Docker port conflict).' }
}
$taskPreviousPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $taskConfig.POSTGRES_PASSWORD
    $taskExists = & (Join-Path $taskBin 'psql.exe') -h 127.0.0.1 -p $taskPort -U $taskUser -d postgres -At -v ON_ERROR_STOP=1 -c "SELECT 1 FROM pg_database WHERE datname = '$taskDatabase'"
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL authentication failed.' }
    if ($taskExists -ne '1') {
        & (Join-Path $taskBin 'createdb.exe') -h 127.0.0.1 -p $taskPort -U $taskUser $taskDatabase
        if ($LASTEXITCODE -ne 0) { throw 'Database creation failed.' }
    }
    & (Join-Path $taskBin 'psql.exe') -h 127.0.0.1 -p $taskPort -U $taskUser -d $taskDatabase -v ON_ERROR_STOP=1 -c 'SELECT version(), current_database();'
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL verification failed.' }
} finally { $env:PGPASSWORD = $taskPreviousPassword }
Write-Output "Local PostgreSQL ready at 127.0.0.1:$taskPort/$taskDatabase"
