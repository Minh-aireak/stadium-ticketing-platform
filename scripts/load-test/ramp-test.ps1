<#
.SYNOPSIS
    Ramp load test against match-catalog-service to find the max sustainable req/s.

.DESCRIPTION
    Runs wrk at increasing concurrency levels against a target URL, 30s per step,
    and summarizes Requests/sec, p99 latency, and errors for each step so the
    saturation point (where req/s plateaus or errors/timeouts appear) can be spotted.

    READ OkReqSec, NOT ReqSec. wrk's Requests/sec counts every response, 503s included, and
    match-catalog-service sheds browse traffic above its catalog-read RateLimiter -- 600/s per
    instance, so 1200/s across the two behind catalog-lb (resilience4j block in the service's
    application.yaml). wrk is closed-loop: 50 connections at ~10ms already push ~4500 req/s, so
    from the very first step roughly three quarters of ReqSec is 503 and OkReqSec sits flat at
    ~1200 no matter the concurrency. That flat line is the limiter doing its job, not the
    service's capacity. ReqSec above it measures how fast the service can REJECT -- a useful
    number in its own right (rejecting must stay cheap, or the shedding is what collapses), but
    not throughput.

    To get a step that is actually below the limiter, drop the concurrency until
    c / latency < 1200 -- and the latency of a cache hit through catalog-lb is ~1.4ms, so
    c=4 already pushes ~2800 req/s and c=2 is borderline: ./ramp-test.ps1 -Concurrencies 1,2.
    c=1 must show 0 non-2xx. (wrk cannot hold a fixed rate; wrk2's -R can, if this ever needs
    a proper under-the-limit sweep.)

    To measure capacity with the limiter out of the way, raise limitForPeriod AND the
    catalog-read bulkhead's maxConcurrentCalls (200 in flight per instance, sheds the same way)
    through a gitignored docker-compose.local.yml that sets SPRING_APPLICATION_JSON on both
    instances, recreate them with -f docker-compose.yaml -f docker-compose.local.yml, then
    `docker compose restart catalog-lb` (nginx resolves upstream IPs at startup). Recreate
    WITHOUT the override afterwards, or the next use-case run measures a service that no longer
    exists.

.PARAMETER Url
    Target URL to load test. Defaults to catalog-lb, the nginx load balancer -- the address
    every real caller uses (api-gateway for /api/v1/matches/**, ticket-inventory-service for
    requireBookable), so the number it produces is the one the platform can actually serve.
    To measure a single JVM instead, point it at one instance:
        ./ramp-test.ps1 -Url "http://match-catalog-service-1:8082/api/v1/matches"
    There is no plain "match-catalog-service" host to target: the service has run as
    match-catalog-service-1/-2 behind catalog-lb since it was replicated, and compose only
    creates DNS aliases for names it declares.

    NOT host.docker.internal, which routes through Docker Desktop's host port-forwarding proxy
    and was measured to roughly halve throughput and inject its own "write" socket errors
    unrelated to the app (7570 vs 15770 req/s at c=1200, 381 spurious write-errors vs 0 --
    measured against the single container that preceded the two-instance topology, so treat the
    absolute figures as historical and the ratio as the point). Pass
    -Url "http://host.docker.internal:8082/api/v1/matches" -Network "" to go back to testing
    through that path, e.g. to measure what an out-of-Docker client sees.

    The default has no ?q=, so it exercises the Guava/Redis/Postgres browse path and never
    touches Elasticsearch -- MatchCatalogService only calls the search adapter when a query is
    present. That blind spot is how the Elasticsearch connection-pool queue went unnoticed:
    see ElasticsearchClientConfig, where 200 concurrent searches against a wedged cluster took
    601s each because the pool leases only ten connections per route. Sweep the search path too:
        ./ramp-test.ps1 -Url "http://catalog-lb:8082/api/v1/matches?q=united"
    Both are worth running. They saturate on completely different resources, and the search one
    shares the catalog-read bulkhead with the browse one, so whichever saturates first takes the
    other down with it. No spaces in the query -- wrk gets the URL verbatim.

.PARAMETER Network
    Docker network to attach the wrk container to, so it can resolve the target by
    service name instead of hopping out to the host and back in. Empty string runs wrk
    unattached (required when -Url targets host.docker.internal). Default is this
    compose project's network -- check `docker network ls` if it's been renamed.

.PARAMETER Concurrencies
    List of -c values (concurrent connections) to sweep through.

.PARAMETER Threads
    wrk thread count (-t). Two, not six: wrk runs INSIDE the same Docker Desktop VM as the two
    catalog JVMs, nginx, Redis and Postgres, all sharing the 8 cores Docker is given. Six wrk
    threads spent a measurable slice of those cores generating load instead of serving it, and
    two threads drive 3000 connections without breaking a sweat. Watch `docker stats` in a second
    terminal while a step runs -- if the containers add up to ~800% CPU, the ceiling you are
    reading is the VM's, not the service's.

.PARAMETER Duration
    Duration per step (wrk -d value, e.g. "30s").

.PARAMETER WarmupSeconds
    Seconds of load at the first concurrency to run and DISCARD before the sweep. The first
    30s against a freshly started JVM is JIT compilation, not the service: measured 814 req/s
    with p99 894ms at c=100 on a cold JVM, and 4850 req/s with p99 78ms on the same step once
    warm. 0 skips the warm-up -- fine when the service has just been hammered by a previous run.

.PARAMETER CooldownSeconds
    Seconds to wait between steps.

.EXAMPLE
    ./ramp-test.ps1

.EXAMPLE
    ./ramp-test.ps1 -Concurrencies 900,1000,1100

.EXAMPLE
    ./ramp-test.ps1 -Concurrencies 1,2 -WarmupSeconds 0
    The only steps that stay below the 1200/s limiter; c=1 must show 0 non-2xx.

.EXAMPLE
    ./ramp-test.ps1 -Url "http://host.docker.internal:8082/api/v1/matches" -Network ""
#>

param(
    [string]$Url = "http://catalog-lb:8082/api/v1/matches",
    [string]$Network = "stadium-ticketing-platform_stadium-net",
    [int[]]$Concurrencies = @(50, 100, 200, 400, 800, 1200, 1600, 2000, 3000),
    [int]$Threads = 2,
    [string]$Duration = "30s",
    [int]$WarmupSeconds = 60,
    [int]$CooldownSeconds = 5
)

function Invoke-Wrk([int]$Connections, [string]$StepDuration) {
    # Splatting, not a bare token string, because Windows PowerShell 5.1 can mis-marshal
    # native-command arguments built from inline variable expansion (e.g. "-t$Threads")
    # -- wrk then sees garbled argv and just prints its usage text instead of running.
    $wrkArgs = @("run", "--rm")
    if ($Network) { $wrkArgs += @("--network", $Network) }
    $wrkArgs += @("williamyeh/wrk", "-t$Threads", "-c$Connections", "-d$StepDuration", "--latency", $Url)
    & docker @wrkArgs
}

# "12345 requests in 30.09s" -- wrk switches the unit to minutes past 60s ("1.00m").
function Get-WrkSeconds([string[]]$Output) {
    $m = ($Output | Select-String "^\s*\d+ requests in ([\d.]+)(s|m)\b") | Select-Object -First 1
    if (-not $m) { return $null }
    $value = [double]$m.Matches[0].Groups[1].Value
    if ($m.Matches[0].Groups[2].Value -eq "m") { $value * 60 } else { $value }
}

function Get-WrkNumber([string[]]$Output, [string]$Pattern) {
    $m = ($Output | Select-String $Pattern) | Select-Object -First 1
    if ($m) { [long]$m.Matches[0].Groups[1].Value } else { 0 }
}

if ($WarmupSeconds -gt 0) {
    Write-Host "`n=== warm-up: concurrency=$($Concurrencies[0]) for ${WarmupSeconds}s (discarded) ===" -ForegroundColor DarkGray
    $warm = Invoke-Wrk -Connections $Concurrencies[0] -StepDuration "${WarmupSeconds}s"
    $warm | Select-String "Requests/sec:|Non-2xx|Socket errors" | ForEach-Object { Write-Host "  $($_.ToString().Trim())" -ForegroundColor DarkGray }
    Start-Sleep -Seconds $CooldownSeconds
}

$results = @()

foreach ($c in $Concurrencies) {
    Write-Host "`n=== concurrency=$c ===" -ForegroundColor Cyan

    $output = Invoke-Wrk -Connections $c -StepDuration $Duration
    $output | Write-Host

    $reqSec   = $output | Select-String "Requests/sec:"
    $p99      = $output | Select-String "^\s*99%"
    $errors   = $output | Select-String "Socket errors|Non-2xx"
    $seconds  = Get-WrkSeconds $output
    $requests = Get-WrkNumber $output "^\s*(\d+) requests in"
    $non2xx   = Get-WrkNumber $output "Non-2xx or 3xx responses:\s*(\d+)"
    $timeouts = Get-WrkNumber $output "timeout (\d+)"

    $okReqSec = if ($seconds) { [math]::Round(($requests - $non2xx) / $seconds, 1) } else { "-" }
    $non2xxPct = if ($requests -gt 0) { [math]::Round(100.0 * $non2xx / $requests, 1) } else { 0 }

    $results += [PSCustomObject]@{
        Concurrency = $c
        ReqSec      = if ($reqSec) { $reqSec.ToString().Trim() -replace "Requests/sec:\s*", "" } else { "-" }
        OkReqSec    = $okReqSec
        "Non2xx%"   = $non2xxPct
        P99         = if ($p99) { $p99.ToString().Trim() -replace "^99%\s*", "" } else { "-" }
        Timeouts    = $timeouts
        Errors      = if ($errors) { ($errors -join "; ") } else { "none" }
    }

    if ($c -ne $Concurrencies[-1]) {
        Start-Sleep -Seconds $CooldownSeconds
    }
}

Write-Host "`n=== SUMMARY ===" -ForegroundColor Yellow
Write-Host "ReqSec = every response wrk got, 503s included. OkReqSec = 2xx only; above the catalog-read limiter it flattens at ~1200 by design." -ForegroundColor DarkGray
$results | Format-Table -AutoSize
