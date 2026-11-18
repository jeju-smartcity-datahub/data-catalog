[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskTools = Join-Path $taskRoot '.tools'
$taskPg = Join-Path $taskTools 'postgresql-17.11/pgsql'
$taskArchive = Join-Path $taskTools 'postgresql-17.11-5-windows-x64-binaries.zip'
$taskUri = 'https://get.enterprisedb.com/postgresql/postgresql-17.11-5-windows-x64-binaries.zip'
# SHA256 of the PostgreSQL archive downloaded from EDB over HTTPS.
$taskHash = '80379b2c04d51c30225532e0ae04509899141e9957ed096fe749d7fd9df8f82f'
if (-not (Test-Path -LiteralPath (Join-Path $taskPg 'bin/postgres.exe'))) {
    New-Item -ItemType Directory -Path $taskTools -Force | Out-Null
    if (-not (Test-Path -LiteralPath $taskArchive)) {
        Invoke-WebRequest -Uri $taskUri -OutFile $taskArchive -TimeoutSec 300
    }
    if ((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash -ne $taskHash) {
        throw 'PostgreSQL archive checksum mismatch; archive preserved for inspection.'
    }
    Expand-Archive -LiteralPath $taskArchive -DestinationPath (Join-Path $taskTools 'postgresql-17.11') -Force
}
& (Join-Path $taskPg 'bin/postgres.exe') --version
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL executable validation failed.' }
