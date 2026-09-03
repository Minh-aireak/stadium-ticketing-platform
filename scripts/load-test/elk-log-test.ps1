<#
.SYNOPSIS
    Fires ~1000 requests at identity-service and match-catalog-service to verify the ELK
    pipeline (Logstash -> Elasticsearch -> Kibana) is actually capturing application logs.

.DESCRIPTION
    Unlike ramp-test.ps1 (raw throughput benchmarking with wrk), this script is a functional
    smoke test: it deliberately exercises a mix of success and failure paths so a distinct
    log line should appear for each of the scenarios below, and you can confirm they show up
    in Kibana rather than just checking HTTP status codes here.

    Hits services directly on their host-mapped ports (bypassing api-gateway) so the gateway's
    per-IP rate limiter -- especially LOGIN's ~1 req/12s sustained rate -- doesn't throttle the
    run down to a crawl or silently swallow requests as 429s before they ever reach a service.

    Scenarios (default 1000 total):
      - CatalogSearch        GET  /api/v1/matches?q=...          success (bulk of the volume)
      - CatalogNotFoundById  GET  /api/v1/matches/{random-id}     404, no matching match
      - CatalogUnmappedRoute GET  /api/v1/does-not-exist          404, no matching route at all
      - RegisterDuplicate    POST /api/v1/auth/register           422, EmailAlreadyRegisteredException
      - LoginUnknownEmail    POST /api/v1/auth/login               401, "no account for supplied email"
      - LoginWrongPassword   POST /api/v1/auth/login               401, "bad-password" (existing account)

    Every request carries a unique X-Correlation-Id ("<prefix>-<n>") built from -CorrelationPrefix,
    which lands as a top-level `correlationId` field in each service's ECS-formatted log line
    (see CorrelationIdFilter). Filter Kibana on that prefix to isolate this run's traffic from
    everything else in the index.

.PARAMETER IdentityUrl
    Base URL for identity-service. Defaults to the host-mapped Docker Compose port.

.PARAMETER CatalogUrl
    Base URL for match-catalog-service. Defaults to the host-mapped Docker Compose port.

.PARAMETER CorrelationPrefix
    Tag prepended to every request's X-Correlation-Id. Defaults to a timestamped value so
    repeated runs don't collide in Kibana.

    Per-scenario request counts follow. Each needs its own .PARAMETER block: comment-based help
    reads everything after the tag to end of line as the parameter NAME, so listing several on one
    line documents a parameter that does not exist and leaves all six of the real ones blank in
    Get-Help. The defaults below sum to 1000; override any of them to change the mix or the volume.

.PARAMETER CatalogSearchCount
    Successful catalog searches. Default 500 — the bulk of the run's volume.

.PARAMETER CatalogNotFoundByIdCount
    Lookups of a random match id, each logged as a 404 with no matching match. Default 100.

.PARAMETER CatalogUnmappedRouteCount
    Requests to a path with no route at all, a different 404 path through the handler. Default 50.

.PARAMETER RegisterDuplicateCount
    Registrations of an address that already exists, logging 422
    EmailAlreadyRegisteredException. Default 100.

.PARAMETER LoginUnknownEmailCount
    Logins for an address with no account, logging 401 "no account for supplied email".
    Default 125.

.PARAMETER LoginWrongPasswordCount
    Logins for an existing account with the wrong password, logging 401 "bad-password".
    Default 125.

.EXAMPLE
    ./elk-log-test.ps1

.EXAMPLE
    # Smaller, faster run against a differently-mapped identity-service port
    ./elk-log-test.ps1 -IdentityUrl "http://localhost:18081" -LoginUnknownEmailCount 20 -LoginWrongPasswordCount 20 -CatalogSearchCount 100 -CatalogNotFoundByIdCount 20 -CatalogUnmappedRouteCount 10 -RegisterDuplicateCount 20
#>

param(
    [string]$IdentityUrl = "http://localhost:8081",
    [string]$CatalogUrl = "http://localhost:8082",
    [string]$CorrelationPrefix = ("elktest-" + (Get-Date -Format "yyyyMMdd-HHmmss")),
    [int]$CatalogSearchCount = 500,
    [int]$CatalogNotFoundByIdCount = 100,
    [int]$CatalogUnmappedRouteCount = 50,
    [int]$RegisterDuplicateCount = 100,
    [int]$LoginUnknownEmailCount = 125,
    [int]$LoginWrongPasswordCount = 125
)

$ErrorActionPreference = "Stop"

$seedEmail = "$CorrelationPrefix-seed@example.com"
$seedPassword = "ElkTest1234"
$wrongPassword = "WrongPass9"
$searchTerms = @("Real", "United", "FC", "City", "Barca", "", "Rovers", "Athletic")

$counter = 0
$stats = @{}

function New-CorrelationId {
    $script:counter++
    return "$CorrelationPrefix-$script:counter"
}

function Record-Result {
    param([string]$Scenario, [string]$StatusBucket)
    $key = "$Scenario|$StatusBucket"
    if (-not $stats.ContainsKey($key)) { $stats[$key] = 0 }
    $stats[$key]++
}

# Wraps Invoke-WebRequest so an expected 4xx/401/422 response is recorded as data, not a
# script-terminating exception -- Windows PowerShell 5.1's Invoke-WebRequest throws on any
# non-2xx status, so the interesting (failure-path) responses have to be read out of the
# WebException's own Response object.
function Invoke-TestRequest {
    param(
        [string]$Scenario,
        [string]$Method,
        [string]$Uri,
        [string]$Body = $null
    )
    $headers = @{ "X-Correlation-Id" = (New-CorrelationId) }
    try {
        if ($Body) {
            Invoke-WebRequest -Method $Method -Uri $Uri -Headers $headers `
                -ContentType "application/json" -Body $Body -UseBasicParsing | Out-Null
        } else {
            Invoke-WebRequest -Method $Method -Uri $Uri -Headers $headers -UseBasicParsing | Out-Null
        }
        Record-Result -Scenario $Scenario -StatusBucket "2xx"
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if ($resp) {
            $code = [int]$resp.StatusCode
            Record-Result -Scenario $Scenario -StatusBucket "$code"
        } else {
            Record-Result -Scenario $Scenario -StatusBucket "ConnFail"
        }
    } catch {
        Record-Result -Scenario $Scenario -StatusBucket "ConnFail"
    }
}

function Show-Progress {
    param([int]$Done, [int]$Total)
    if ($Done % 100 -eq 0 -or $Done -eq $Total) {
        Write-Host ("  {0}/{1} requests sent" -f $Done, $Total) -ForegroundColor DarkGray
    }
}

$totalRequests = $CatalogSearchCount + $CatalogNotFoundByIdCount + $CatalogUnmappedRouteCount `
    + $RegisterDuplicateCount + $LoginUnknownEmailCount + $LoginWrongPasswordCount
$done = 0
$sw = [System.Diagnostics.Stopwatch]::StartNew()

Write-Host "=== ELK log smoke test ===" -ForegroundColor Cyan
Write-Host "Correlation prefix: $CorrelationPrefix"
Write-Host "Total requests: $totalRequests`n"

# Seed one real account up front -- RegisterDuplicate and LoginWrongPassword both need an
# account that genuinely exists, so their failure is the specific branch under test (duplicate
# email / wrong password) rather than "unknown account" masking it.
Write-Host "--- Seeding test account ($seedEmail) ---" -ForegroundColor Cyan
$seedBody = (@{ email = $seedEmail; password = $seedPassword } | ConvertTo-Json -Compress)
Invoke-TestRequest -Scenario "SeedRegister" -Method "POST" -Uri "$IdentityUrl/api/v1/auth/register" -Body $seedBody

Write-Host "`n--- CatalogSearch ($CatalogSearchCount) ---" -ForegroundColor Cyan
for ($i = 0; $i -lt $CatalogSearchCount; $i++) {
    $q = $searchTerms[$i % $searchTerms.Length]
    $page = $i % 4
    $size = 10 + (($i % 5) * 10)
    Invoke-TestRequest -Scenario "CatalogSearch" -Method "GET" `
        -Uri "$CatalogUrl/api/v1/matches?q=$q&page=$page&size=$size"
    $done++; Show-Progress -Done $done -Total $totalRequests
}

Write-Host "`n--- CatalogNotFoundById ($CatalogNotFoundByIdCount) ---" -ForegroundColor Cyan
for ($i = 0; $i -lt $CatalogNotFoundByIdCount; $i++) {
    $bogusId = [guid]::NewGuid().ToString()
    Invoke-TestRequest -Scenario "CatalogNotFoundById" -Method "GET" `
        -Uri "$CatalogUrl/api/v1/matches/$bogusId"
    $done++; Show-Progress -Done $done -Total $totalRequests
}

Write-Host "`n--- CatalogUnmappedRoute ($CatalogUnmappedRouteCount) ---" -ForegroundColor Cyan
for ($i = 0; $i -lt $CatalogUnmappedRouteCount; $i++) {
    Invoke-TestRequest -Scenario "CatalogUnmappedRoute" -Method "GET" `
        -Uri "$CatalogUrl/api/v1/does-not-exist-$i"
    $done++; Show-Progress -Done $done -Total $totalRequests
}

Write-Host "`n--- RegisterDuplicate ($RegisterDuplicateCount) ---" -ForegroundColor Cyan
for ($i = 0; $i -lt $RegisterDuplicateCount; $i++) {
    Invoke-TestRequest -Scenario "RegisterDuplicate" -Method "POST" `
        -Uri "$IdentityUrl/api/v1/auth/register" -Body $seedBody
    $done++; Show-Progress -Done $done -Total $totalRequests
}

Write-Host "`n--- LoginUnknownEmail ($LoginUnknownEmailCount) ---" -ForegroundColor Cyan
for ($i = 0; $i -lt $LoginUnknownEmailCount; $i++) {
    $unknownEmail = "nouser-$([guid]::NewGuid().ToString('N'))@example.com"
    $body = (@{ email = $unknownEmail; password = $wrongPassword } | ConvertTo-Json -Compress)
    Invoke-TestRequest -Scenario "LoginUnknownEmail" -Method "POST" `
        -Uri "$IdentityUrl/api/v1/auth/login" -Body $body
    $done++; Show-Progress -Done $done -Total $totalRequests
}

Write-Host "`n--- LoginWrongPassword ($LoginWrongPasswordCount) ---" -ForegroundColor Cyan
$wrongBody = (@{ email = $seedEmail; password = $wrongPassword } | ConvertTo-Json -Compress)
for ($i = 0; $i -lt $LoginWrongPasswordCount; $i++) {
    Invoke-TestRequest -Scenario "LoginWrongPassword" -Method "POST" `
        -Uri "$IdentityUrl/api/v1/auth/login" -Body $wrongBody
    $done++; Show-Progress -Done $done -Total $totalRequests
}

$sw.Stop()

Write-Host "`n=== SUMMARY ===" -ForegroundColor Yellow
$stats.GetEnumerator() | Sort-Object Name | ForEach-Object {
    $parts = $_.Name -split '\|'
    [PSCustomObject]@{ Scenario = $parts[0]; Status = $parts[1]; Count = $_.Value }
} | Format-Table -AutoSize

$reqPerSec = if ($sw.Elapsed.TotalSeconds -gt 0) { [math]::Round($totalRequests / $sw.Elapsed.TotalSeconds, 1) } else { "-" }
Write-Host "Elapsed: $([math]::Round($sw.Elapsed.TotalSeconds, 1))s ($reqPerSec req/s)`n"

Write-Host "Kibana: filter on correlationId : ""$CorrelationPrefix*"" to isolate this run's log lines." -ForegroundColor Green
