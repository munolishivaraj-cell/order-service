<#
.SYNOPSIS
  Captures the README screenshots into docs/screenshots and pushes them to GitHub.

.DESCRIPTION
  Run from the repository root, with Docker Desktop running:
      powershell -ExecutionPolicy Bypass -File .\scripts\capture-screenshots.ps1

  What it does:
    1. Commits and pushes any pending changes, so GitHub Actions CI starts a fresh run.
    2. Starts the Docker Compose stack and waits until the app is healthy.
    3. Runs generate-traffic.ps1 in THIS window, then captures the window
       -> generate-traffic-terminal.png   (keep this window in front while traffic runs)
    4. Captures pages with headless Edge/Chrome:
       -> prometheus-target-up.png        http://localhost:9090/targets
       -> jaeger-trace-create-order.png   a POST /api/v1/orders trace with the create-order span
       -> swagger-ui-orders-api.png       http://localhost:8080/swagger-ui.html (Orders endpoints)
    5. Waits for the CI run of the pushed commit to finish green and captures it
       -> github-actions-ci-green.png
    6. Commits docs/screenshots and pushes.
  The Grafana screenshot is taken by hand (Grafana needs a login);
  existing files in docs/screenshots are kept.
#>
param(
    [int]$TrafficRounds = 90,      # about 3 minutes of traffic
    [switch]$SkipCi,               # don't wait for / capture the GitHub Actions run
    [switch]$SkipPush              # capture only, no git commit/push
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$out = Join-Path $root "docs\screenshots"
New-Item -ItemType Directory -Force $out | Out-Null
$repo = "munolishivaraj-cell/order-service"

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

# ---------- helpers ----------------------------------------------------------
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class ShotWin {
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@
[ShotWin]::SetProcessDPIAware() | Out-Null

function Save-WindowShot($file) {
    $h = [ShotWin]::GetForegroundWindow()
    $r = New-Object ShotWin+RECT
    [ShotWin]::GetWindowRect($h, [ref]$r) | Out-Null
    $w = $r.Right - $r.Left; $hgt = $r.Bottom - $r.Top
    $bmp = New-Object System.Drawing.Bitmap $w, $hgt
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($r.Left, $r.Top, 0, 0, $bmp.Size)
    $bmp.Save((Join-Path $out $file), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    Write-Host "saved $file" -ForegroundColor Green
}

$browser = @(
    "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
    "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
    "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $browser) { throw "Microsoft Edge or Google Chrome is needed for the page screenshots." }

function Save-PageShot($url, $file, [int]$width = 1600, [int]$height = 1000, [int]$waitMs = 15000) {
    $path = Join-Path $out $file
    if (Test-Path $path) { Remove-Item $path }
    $browserArgs = @(
        "--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-first-run",
        "--user-data-dir=$(Join-Path $env:TEMP 'order-service-shots')",
        "--force-device-scale-factor=1", "--window-size=$width,$height",
        "--virtual-time-budget=$waitMs", "--run-all-compositor-stages-before-draw",
        "--screenshot=$path", $url)
    $ErrorActionPreference = "Continue"   # browser log lines on stderr are not errors
    & $browser @browserArgs 2>$null | Out-Null
    if (Test-Path $path) { Write-Host "saved $file" -ForegroundColor Green }
    else { Write-Host "could not capture $url" -ForegroundColor Red }
}

function Wait-Until($what, [scriptblock]$check, [int]$timeoutSec = 300, [int]$everySec = 5) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try { $v = & $check; if ($v) { return $v } } catch { }
        Start-Sleep -Seconds $everySec
    }
    throw "Timed out waiting for $what"
}

# ---------- 1. push pending changes so CI runs -----------------------------
if (-not $SkipPush -and (git status --porcelain)) {
    Write-Step "Committing and pushing pending changes (CI will start on GitHub)"
    git add -A
    git commit -m "Add CI test summary and screenshot capture script"
    git push
}
$sha = (git rev-parse HEAD).Trim()

# ---------- 2. stack ---------------------------------------------------------
Write-Step "Starting the Docker Compose stack"
docker compose up -d --build
if ($LASTEXITCODE -ne 0) { throw "docker compose failed. Is Docker Desktop running?" }
Wait-Until "the app to be healthy" { (Invoke-RestMethod "http://localhost:8080/actuator/health").status -eq "UP" } | Out-Null

# ---------- 3. traffic + terminal screenshot ---------------------------------
Write-Step "Generating traffic for the dashboards. Keep this window in front."
Start-Sleep -Seconds 2
Clear-Host
Write-Host "PS $root> .\scripts\generate-traffic.ps1 -Rounds $TrafficRounds"
& (Join-Path $PSScriptRoot "generate-traffic.ps1") -Rounds $TrafficRounds
Start-Sleep -Milliseconds 800
Save-WindowShot "generate-traffic-terminal.png"

# ---------- 4. Swagger, Prometheus, Jaeger ----------------------------------
Write-Step "Capturing Swagger UI"
Save-PageShot "http://localhost:8080/swagger-ui/index.html#/Orders" "swagger-ui-orders-api.png" 1600 1250

Write-Step "Capturing Prometheus targets"
Wait-Until "Prometheus to report order-service UP" {
    $t = (Invoke-RestMethod "http://localhost:9090/api/v1/targets").data.activeTargets |
         Where-Object { $_.labels.job -eq "order-service" }
    $t -and $t.health -eq "up"
} 120 | Out-Null
Save-PageShot "http://localhost:9090/targets" "prometheus-target-up.png" 1600 900

Write-Step "Capturing a create-order trace in Jaeger"
$traceId = Wait-Until "a create-order trace in Jaeger" {
    $traces = (Invoke-RestMethod "http://localhost:16686/api/traces?service=order-service&limit=200&lookback=1h").data
    ($traces | Where-Object { $_.spans | Where-Object { $_.operationName -eq "create-order" } } | Select-Object -First 1).traceID
} 120
Save-PageShot "http://localhost:16686/trace/$traceId" "jaeger-trace-create-order.png" 1600 900

# ---------- 5. GitHub Actions ------------------------------------------------
if (-not $SkipCi) {
    Write-Step "Waiting for the CI run of $($sha.Substring(0,7)) on GitHub (can take ~5 minutes)"
    $run = Wait-Until "the CI run to finish" {
        $r = (Invoke-RestMethod "https://api.github.com/repos/$repo/actions/runs?head_sha=$sha&per_page=20" -Headers @{ "User-Agent" = "order-service-screenshots" }).workflow_runs |
             Where-Object { $_.name -eq "CI" } | Select-Object -First 1
        if ($r -and $r.status -eq "completed") { $r }
    } 1200 30
    if ($run.conclusion -eq "success") {
        Save-PageShot $run.html_url "github-actions-ci-green.png" 1600 1400 20000
    } else {
        Write-Host "CI finished with '$($run.conclusion)', not capturing it: $($run.html_url)" -ForegroundColor Red
    }
}

# ---------- 6. commit screenshots -----------------------------------------
Write-Step "Screenshots in docs\screenshots:"
Get-ChildItem $out -Filter *.png | Format-Table Name, Length -AutoSize
if (-not $SkipPush) {
    git add docs/screenshots
    if (git status --porcelain docs/screenshots) {
        git commit -m "Add README screenshots"
        git push
        Write-Host "`nDone: https://github.com/$repo#screenshots" -ForegroundColor Green
    }
}
