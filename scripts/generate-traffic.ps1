<#
.SYNOPSIS
  Generates realistic order traffic so the Grafana dashboard and Jaeger traces have data.

.DESCRIPTION
  Each round creates an order and moves it through its lifecycle:
    most orders:  CREATED -> PAID -> SHIPPED -> DELIVERED
    some orders:  CREATED -> CANCELLED  or  CREATED -> PAID -> CANCELLED
  It also sends a few bad requests (invalid transition 409, unknown order 404, validation 400)
  so error handling shows up in metrics and traces.

  Start the stack first:  docker compose up -d --build
  Then run:               .\scripts\generate-traffic.ps1              (runs until Ctrl+C)
                          .\scripts\generate-traffic.ps1 -Rounds 50   (stops after 50 orders)
  If scripts are blocked: powershell -ExecutionPolicy Bypass -File .\scripts\generate-traffic.ps1
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Rounds = 0,           # 0 = run until Ctrl+C
    [int]$DelayMs = 300         # pause between requests
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # no "Reading web response" progress bar, and faster requests
$orders = "$BaseUrl/api/v1/orders"
$customers = "alice", "bob", "carol", "dave", "erin", "frank"
$products = @{ "SKU-KEYBOARD" = 49.99; "SKU-MOUSE" = 19.50; "SKU-MONITOR" = 189.00; "SKU-CABLE" = 6.25; "SKU-DOCK" = 129.90 }
$stats = @{ created = 0; statusChanges = 0; expectedErrors = 0; failures = 0 }

function Invoke-Api($method, $url, $body) {
    $params = @{ Method = $method; Uri = $url; ContentType = "application/json"; UseBasicParsing = $true }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Depth 5) }
    try {
        $r = Invoke-WebRequest @params
        return @{ code = [int]$r.StatusCode; json = ($r.Content | ConvertFrom-Json) }
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) { return @{ code = [int]$_.Exception.Response.StatusCode; json = $null } }
        throw
    }
}

function New-Order {
    $items = @()
    foreach ($sku in ($products.Keys | Get-Random -Count (Get-Random -Minimum 1 -Maximum 4))) {
        $items += @{ productCode = $sku; quantity = (Get-Random -Minimum 1 -Maximum 5); unitPrice = $products[$sku] }
    }
    $r = Invoke-Api "Post" $orders @{ customerId = ($customers | Get-Random); items = $items }
    if ($r.code -ne 201) { $stats.failures++; return $null }
    $stats.created++
    return $r.json.id
}

function Set-OrderStatus($id, $status) {
    Start-Sleep -Milliseconds $DelayMs
    $r = Invoke-Api "Patch" "$orders/$id/status" @{ status = $status }
    if ($r.code -eq 200) { $stats.statusChanges++ } else { $stats.failures++ }
}

try { Invoke-RestMethod "$BaseUrl/actuator/health" | Out-Null }
catch { Write-Host "order-service is not reachable at $BaseUrl. Start it with: docker compose up -d --build" -ForegroundColor Red; exit 1 }

Write-Host "Generating traffic against $BaseUrl (Ctrl+C to stop)..." -ForegroundColor Cyan
$round = 0
while ($Rounds -eq 0 -or $round -lt $Rounds) {
    $round++
    $id = New-Order
    if ($id) {
        $roll = Get-Random -Maximum 10
        if ($roll -lt 6)     { "PAID", "SHIPPED", "DELIVERED" | ForEach-Object { Set-OrderStatus $id $_ } }
        elseif ($roll -lt 8) { Set-OrderStatus $id "PAID"; Set-OrderStatus $id "SHIPPED" }
        elseif ($roll -lt 9) { Set-OrderStatus $id "CANCELLED" }
        else                 { Set-OrderStatus $id "PAID"; Set-OrderStatus $id "CANCELLED" }

        # Read it back and list the customer's orders (GET traffic)
        Invoke-Api "Get" "$orders/$id" $null | Out-Null
        Invoke-Api "Get" "$orders`?customerId=alice&size=5" $null | Out-Null
    }

    # Every 5th round: requests that are rejected on purpose
    if ($round % 5 -eq 0) {
        if ($id) { if ((Invoke-Api "Patch" "$orders/$id/status" @{ status = "CREATED" }).code -eq 409) { $stats.expectedErrors++ } }
        if ((Invoke-Api "Get" "$orders/00000000-0000-0000-0000-000000000000" $null).code -eq 404) { $stats.expectedErrors++ }
        if ((Invoke-Api "Post" $orders @{ customerId = ""; items = @() }).code -eq 400) { $stats.expectedErrors++ }
    }

    Write-Host ("round {0,4}: created {1}, status changes {2}, expected 4xx {3}, unexpected failures {4}" -f `
        $round, $stats.created, $stats.statusChanges, $stats.expectedErrors, $stats.failures)
    Start-Sleep -Milliseconds $DelayMs
}

Write-Host "`nDone. Grafana: http://localhost:3000 (Order Service dashboard)   Jaeger: http://localhost:16686" -ForegroundColor Green
