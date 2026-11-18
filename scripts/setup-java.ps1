[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskTools = Join-Path $taskRoot '.tools'
$taskJava = Join-Path $taskTools 'jdk-21.0.12.1+1'
$taskArchive = Join-Path $taskTools 'temurin-21.0.12.1-windows-x64.zip'
$taskUri = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip'
$taskHash = 'f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e'
if (-not (Test-Path -LiteralPath (Join-Path $taskJava 'bin/javac.exe'))) {
    New-Item -ItemType Directory -Path $taskTools -Force | Out-Null
    if (-not (Test-Path -LiteralPath $taskArchive)) {
        Invoke-WebRequest -Uri $taskUri -OutFile $taskArchive
    }
    if ((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash -ne $taskHash) {
        throw 'JDK checksum mismatch. The downloaded file was preserved for inspection.'
    }
    Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskTools -Force
}
& (Join-Path $taskJava 'bin/java.exe') -version
if ($LASTEXITCODE -ne 0) { throw 'Java validation failed' }
Write-Output "Project Java home: $taskJava"
