# Localink boot smoke: start server -> probe /ping -> kill process tree -> verify cleanup
# Usage: powershell -File scripts/smoke.ps1
# Note: java on PATH is the Oracle javapath shim which spawns the real JVM as a CHILD
#       process. Stop-Process only kills the shim, so use taskkill /F /T on the whole
#       tree and verify cleanup afterwards.
$ErrorActionPreference = "Stop"

$jar = Join-Path $PSScriptRoot "..\localink-server\target\localink-server-0.0.1-SNAPSHOT.jar"
if (-not (Test-Path -LiteralPath $jar)) {
    Write-Error "jar not found: $jar (run .\mvnw.cmd clean package first)"
    exit 1
}

if (Get-NetTCPConnection -LocalPort 8086 -State Listen -ErrorAction SilentlyContinue) {
    Write-Error "port 8086 already in use, clean up leftover processes first"
    exit 1
}

$logDir = Join-Path $env:TEMP "localink-smoke"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$proc = Start-Process java -ArgumentList "-jar",$jar -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir "server-out.log") `
    -RedirectStandardError (Join-Path $logDir "server-err.log")
Write-Output "STARTED: shim PID=$($proc.Id), logs in $logDir"

try {
    $ok = $false
    foreach ($i in 1..30) {
        Start-Sleep -Seconds 1
        try {
            if ((Invoke-RestMethod -Uri "http://localhost:8086/ping" -TimeoutSec 2) -eq "pong") {
                $ok = $true
                break
            }
        } catch {
        }
    }
    if (-not $ok) {
        Write-Error "smoke failed: /ping did not return pong within 30s, check logs in $logDir"
        exit 1
    }
    Write-Output "PING-OK: pong"
} finally {
    taskkill /F /T /PID $proc.Id | Out-Null
    Start-Sleep -Seconds 2

    $leftover = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.ProcessId -eq $proc.Id -or $_.ParentProcessId -eq $proc.Id }
    $portHeld = Get-NetTCPConnection -LocalPort 8086 -State Listen -ErrorAction SilentlyContinue
    if ($leftover -or $portHeld) {
        Write-Error "cleanup failed: leftover process or port 8086 still held, run taskkill /F /T manually"
        exit 1
    }
    Write-Output "CLEANUP-OK: process tree killed, port 8086 released"
}
