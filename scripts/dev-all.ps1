# dev-all.ps1 - 一键启动 Localink 前后端（W4 验收：一键起前后端）
# 用法：
#   powershell -File scripts\dev-all.ps1              # 后端(8086) + 前端 dev(5173)
#   powershell -File scripts\dev-all.ps1 -DevSms      # 额外开启 dev 取码接口（演示用，登录页自动回填验证码）
# 停止：Ctrl+C 或关闭窗口（脚本退出时自动清理双端进程树）
param(
    [switch]$DevSms
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'localink-server\target\localink-server-0.0.1-SNAPSHOT.jar'
$logDir = Join-Path $env:TEMP 'localink-dev-all'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Assert-PortFree([int]$Port) {
    $busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($busy) {
        Write-Host "[X] 端口 $Port 已被占用（PID $($busy[0].OwningProcess)）。请先停掉旧进程再运行本脚本。" -ForegroundColor Red
        exit 1
    }
}

function Wait-Http([string]$Url, [string]$Name, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-RestMethod -Uri $Url -TimeoutSec 2 | Out-Null
            Write-Host "[OK] $Name 就绪" -ForegroundColor Green
            return $true
        } catch { Start-Sleep -Seconds 2 }
    }
    Write-Host "[X] $Name 在 ${TimeoutSec}s 内未就绪，查看日志：$logDir" -ForegroundColor Red
    return $false
}

Assert-PortFree 8086
Assert-PortFree 5173

if (-not (Test-Path $jar)) {
    Write-Host "[!] 未找到后端 jar，先构建（跳过测试）..." -ForegroundColor Yellow
    Push-Location $root
    .\mvnw.cmd clean package "-DskipTests" | Out-Null
    Pop-Location
}

# ---- 后端 ----
$backendArgs = @('-jar', $jar)
if ($DevSms) { $backendArgs += '--localink.dev.sms-code-query.enabled=true' }
Write-Host "[..] 启动后端 8086 ..." -ForegroundColor Cyan
$backend = Start-Process java -ArgumentList $backendArgs -WorkingDirectory (Join-Path $root 'localink-server') `
    -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$logDir\backend.log" -RedirectStandardError "$logDir\backend.err.log"
if (-not (Wait-Http 'http://localhost:8086/ping' '后端(8086)' 90)) { Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue; exit 1 }

# ---- 前端 ----
if (-not (Test-Path (Join-Path $root 'localink-web\node_modules'))) {
    Write-Host "[!] 首次运行，安装前端依赖 ..." -ForegroundColor Yellow
    Push-Location (Join-Path $root 'localink-web')
    npm install | Out-Null
    Pop-Location
}
Write-Host "[..] 启动前端 dev 5173 ..." -ForegroundColor Cyan
$frontend = Start-Process npm.cmd -ArgumentList 'run', 'dev' -WorkingDirectory (Join-Path $root 'localink-web') `
    -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$logDir\frontend.log" -RedirectStandardError "$logDir\frontend.err.log"
if (-not (Wait-Http 'http://localhost:5173/' '前端(5173)' 30)) {
    Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $frontend.Id -Force -ErrorAction SilentlyContinue
    exit 1
}

Write-Host ""
Write-Host "==================== Localink 已启动 ====================" -ForegroundColor Green
Write-Host "  C 端      http://localhost:5173/        （商户/秒杀/社区/搜索）"
Write-Host "  运营后台  http://localhost:5173/admin    （守卫：登录即可）"
if ($DevSms) { Write-Host "  dev 取码接口已开启：登录页自动回填验证码（仅演示！对外部署必须关）" -ForegroundColor Yellow }
Write-Host "  演示剧本  docs/demo-script.md（10 分钟一条龙走查）"
Write-Host "  日志目录  $logDir"
Write-Host "  停止：Ctrl+C（自动清理双端进程树）"
Write-Host "==========================================================" -ForegroundColor Green

# ---- 退出时清理双端进程树 ----
$cleanup = {
    foreach ($pid_ in @($frontend.Id, $backend.Id)) {
        if ($pid_) { taskkill /F /T /PID $pid_ 2>$null | Out-Null }
    }
    Write-Host "[OK] 前后端已停止" -ForegroundColor Green
}
try {
    Register-EngineEvent PowerShell.Exiting -Action $cleanup | Out-Null
    # 前台等待：任一进程退出即结束（Ctrl+C 走 finally）
    while (-not $backend.HasExited -and -not $frontend.HasExited) { Start-Sleep -Seconds 2 }
} finally {
    & $cleanup
}
