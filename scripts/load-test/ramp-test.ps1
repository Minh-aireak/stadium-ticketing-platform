<#
.SYNOPSIS
    Ramp load test against match-catalog-service to find the max sustainable req/s.

.DESCRIPTION
    Runs wrk at increasing concurrency levels against a target URL, 30s per step,
    and summarizes Requests/sec, p99 latency, and errors for each step so the
    saturation point (where req/s plateaus or errors/timeouts appear) can be spotted.

.PARAMETER Url
    Target URL to load test. Defaults to the in-network service name (see -Network) --
    NOT host.docker.internal, which routes through Docker Desktop's host port-forwarding
    proxy and was measured to roughly halve throughput and inject its own "write" socket
    errors unrelated to the app (7570 vs 15770 req/s at c=1200, 381 spurious write-errors
    vs 0). Pass -Url "http://host.docker.internal:8082/api/v1/matches" -Network "" to go
    back to testing through that path, e.g. to measure what an out-of-Docker client sees.

.PARAMETER Network
    Docker network to attach the wrk container to, so it can resolve the target by
    service name instead of hopping out to the host and back in. Empty string runs wrk
    unattached (required when -Url targets host.docker.internal). Default is this
    compose project's network -- check `docker network ls` if it's been renamed.

.PARAMETER Concurrencies
    List of -c values (concurrent connections) to sweep through.

.PARAMETER Threads
    wrk thread count (-t).

.PARAMETER Duration
    Duration per step (wrk -d value, e.g. "30s").

.PARAMETER CooldownSeconds
    Seconds to wait between steps.

.EXAMPLE
    ./ramp-test.ps1

.EXAMPLE
    ./ramp-test.ps1 -Concurrencies 900,1000,1100

.EXAMPLE
    ./ramp-test.ps1 -Url "http://host.docker.internal:8082/api/v1/matches" -Network ""
#>

param(
    [string]$Url = "http://match-catalog-service:8082/api/v1/matches",
    [string]$Network = "stadium-ticketing-platform_stadium-net",
    [int[]]$Concurrencies = @(50, 100, 200, 400, 800, 1200, 1600, 2000, 3000),
    [int]$Threads = 6,
    [string]$Duration = "30s",
    [int]$CooldownSeconds = 5
)

$results = @()

foreach ($c in $Concurrencies) {
    Write-Host "`n=== concurrency=$c ===" -ForegroundColor Cyan

    # Splatting, not a bare token string, because Windows PowerShell 5.1 can mis-marshal
    # native-command arguments built from inline variable expansion (e.g. "-t$Threads")
    # -- wrk then sees garbled argv and just prints its usage text instead of running.
    $wrkArgs = @("run", "--rm")
    if ($Network) { $wrkArgs += @("--network", $Network) }
    $wrkArgs += @("williamyeh/wrk", "-t$Threads", "-c$c", "-d$Duration", "--latency", $Url)
    $output = & docker @wrkArgs
    $output | Write-Host

    $reqSec = $output | Select-String "Requests/sec:"
    $p99    = $output | Select-String "^\s*99%"
    $errors = $output | Select-String "Socket errors|Non-2xx"

    $results += [PSCustomObject]@{
        Concurrency = $c
        ReqSec      = if ($reqSec) { $reqSec.ToString().Trim() } else { "-" }
        P99         = if ($p99) { $p99.ToString().Trim() } else { "-" }
        Errors      = if ($errors) { ($errors -join "; ") } else { "none" }
    }

    if ($c -ne $Concurrencies[-1]) {
        Start-Sleep -Seconds $CooldownSeconds
    }
}

Write-Host "`n=== SUMMARY ===" -ForegroundColor Yellow
$results | Format-Table -AutoSize
