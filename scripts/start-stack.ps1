<#
.SYNOPSIS
    Bring the stack up one stage at a time, waiting for each to turn healthy before the next.

.DESCRIPTION
    A plain `docker compose up -d` starts everything whose dependencies are met at the same
    moment. Once Postgres, Redis and Kafka are healthy, seven service JVMs, Kafka Connect,
    Logstash and Kibana all boot together -- every one JIT-compiling and filling its heap at
    once, on a Docker VM of 8 logical CPUs and 10 GB (the dev box's %USERPROFILE%\.wslconfig;
    `docker info` shows what yours has). The limits in docker-compose.yaml
    (x-jvm-resources) cap what each container may take, not how many start at once: nine JVMs
    at cpus "2" still ask for 18 CPUs on 8, and their memory limits alone add up to 9 of the
    VM's 10 GB.

    This script starts one stage, waits until it is healthy (or, for the two one-shot init
    containers, exited 0), and only then starts the next, so at most one JVM is ever booting.
    It never starts the four exporters (postgres-, redis-, kafka-, node-exporter), which a plain
    `up -d` would. Start one only when a dashboard needs it:
        docker compose up -d --no-deps kafka-exporter

    Every stage runs with --no-deps, so the stage order below IS the dependency order: compose
    is never asked to start something this script has not already brought up. Two places where
    that order is stricter than the one written down by hand before: the init containers are
    waited on until they EXIT 0, because match-catalog-service needs the Elasticsearch accounts
    to exist, not merely the container to have started; and each nginx load balancer comes up
    right after its two instances, before booking-service, which calls inventory through it.

    Re-running is safe and is how to resume after a failure: a stage that is already healthy
    returns at once, and a container whose image or config changed (after a build, say) is
    recreated by its stage.

    First run on the dev box (2026-09-23): 9.9 min end to end, each service JVM healthy in
    18-58s, every healthchecked container healthy with no OOM kill, and 7.2 of the VM's
    9.7 GiB in use at the end, without Kibana.

.PARAMETER WithKibana
    Also start Kibana, last. Off by default: ~700 MB of a VM that is already near its ceiling,
    and nothing needs it -- logs still reach Elasticsearch through Logstash, and can be searched
    there without it.

.PARAMETER SkipObservability
    Leave Logstash, Prometheus and Grafana (and Kibana) down, for a run that only needs the
    platform itself. The services keep writing their log files; Logstash picks them up whenever
    it is started later.

.PARAMETER WaitTimeoutSeconds
    How long one stage may take to turn healthy before the script stops and prints its logs.
    Six minutes by default, which matches the services' own healthcheck window
    (start_period 60s + 20 retries x 15s) -- a JVM limited to 2 CPUs boots noticeably slower.

.EXAMPLE
    ./scripts/start-stack.ps1

.EXAMPLE
    ./scripts/start-stack.ps1 -WithKibana

.EXAMPLE
    ./scripts/start-stack.ps1 -SkipObservability
    Platform only -- the lightest configuration for a load test.
#>

param(
    [switch]$WithKibana,
    [switch]$SkipObservability,
    [int]$WaitTimeoutSeconds = 360
)

# -f rather than Set-Location, so the script runs from any directory. Compose still reads .env
# from the compose file's own directory and still names the project after it, so containers,
# volumes and the network are the same ones a plain `docker compose` in the repo root sees.
$ComposeFile = Join-Path $PSScriptRoot "..\docker-compose.yaml"
$Compose = @("compose", "-f", $ComposeFile)

# Deliberately not $ErrorActionPreference = "Stop": in Windows PowerShell 5.1 that turns every
# line docker compose writes to stderr -- its normal progress output -- into a terminating
# error. Failures are detected from $LASTEXITCODE instead.

function Show-Failure([string]$Stage, [string[]]$Services) {
    Write-Host ""
    Write-Host "!! Stage '$Stage' did not come up. State and last log lines:" -ForegroundColor Red
    $psArgs = $Compose + @("ps", "-a") + $Services
    & docker @psArgs
    foreach ($svc in $Services) {
        Write-Host ""
        Write-Host "---- $svc (last 40 lines)" -ForegroundColor Yellow
        $logArgs = $Compose + @("logs", "--no-log-prefix", "--tail", "40", $svc)
        & docker @logArgs
    }
    Write-Host ""
    Write-Host "Fix the cause, then run this script again -- healthy stages are skipped over quickly." -ForegroundColor Red
    exit 1
}

# One-shot containers (elasticsearch-init, kafka-connect-init) never become "healthy"; they do
# their work and exit. What the next stage depends on is that they exited 0.
function Wait-OneShot([string]$Stage, [string]$Service) {
    $idArgs = $Compose + @("ps", "-a", "-q", $Service)
    $id = & docker @idArgs | Select-Object -First 1
    if (-not $id) { Show-Failure $Stage @($Service) }

    $deadline = (Get-Date).AddSeconds($WaitTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $state = & docker inspect -f "{{.State.Status}} {{.State.ExitCode}}" $id
        $status, $code = "$state".Trim() -split " "
        if ($status -eq "exited") {
            if ($code -eq "0") { return }
            Show-Failure $Stage @($Service)
        }
        Start-Sleep -Seconds 2
    }
    Show-Failure $Stage @($Service)
}

function Start-Stage([string]$Name, [string[]]$Services, [switch]$OneShot) {
    $started = Get-Date
    Write-Host ""
    Write-Host ("==> {0}: {1}" -f $Name, ($Services -join ", ")) -ForegroundColor Cyan

    if ($OneShot) {
        $upArgs = $Compose + @("up", "-d", "--no-deps") + $Services
        & docker @upArgs
        if ($LASTEXITCODE -ne 0) { Show-Failure $Name $Services }
        foreach ($svc in $Services) { Wait-OneShot $Name $svc }
    } else {
        # --wait blocks until every listed service is healthy (or merely running, for the ones
        # without a healthcheck, like frontend) and exits non-zero if one fails or times out.
        $upArgs = $Compose + @("up", "-d", "--no-deps", "--wait", "--wait-timeout", "$WaitTimeoutSeconds") + $Services
        & docker @upArgs
        if ($LASTEXITCODE -ne 0) { Show-Failure $Name $Services }
    }

    Write-Host ("    ok in {0:N0}s" -f ((Get-Date) - $started).TotalSeconds) -ForegroundColor Green
}

# ---------------------------------------------------------------------------------------------

$null = & docker info --format "{{.ServerVersion}}" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker engine is not reachable. Start Docker Desktop, wait for it, then run this again." -ForegroundColor Red
    exit 1
}

$begin = Get-Date

# Infrastructure. The datastores go together: Postgres and Redis start in seconds and barely
# touch the CPU, so staging them one by one would only cost time.
Start-Stage "Datastores" @("postgres-identity", "postgres-inventory", "postgres-booking",
                           "postgres-payment", "postgres-catalog", "postgres-notification", "redis")
Start-Stage "Kafka" @("kafka")
Start-Stage "Elasticsearch" @("elasticsearch")
Start-Stage "Elasticsearch accounts" @("elasticsearch-init") -OneShot
Start-Stage "Kafka Connect" @("kafka-connect")
Start-Stage "Debezium connectors" @("kafka-connect-init") -OneShot

# Service JVMs, one at a time -- the whole point of this script.
Start-Stage "identity-service" @("identity-service")
Start-Stage "match-catalog-service-1" @("match-catalog-service-1")
Start-Stage "match-catalog-service-2" @("match-catalog-service-2")
Start-Stage "catalog-lb" @("catalog-lb")
Start-Stage "ticket-inventory-service-1" @("ticket-inventory-service-1")
Start-Stage "ticket-inventory-service-2" @("ticket-inventory-service-2")
Start-Stage "ticket-inventory-lb" @("ticket-inventory-lb")
Start-Stage "payment-service" @("payment-service")
Start-Stage "notification-service" @("notification-service")
Start-Stage "booking-service" @("booking-service")
Start-Stage "api-gateway" @("api-gateway")
Start-Stage "frontend" @("frontend")

# Observability last: none of it is on a request's path, and Logstash's JRuby boot is as heavy
# as a service JVM's. Log lines written before it starts are files on app_logs and are not lost.
if (-not $SkipObservability) {
    Start-Stage "Prometheus + Grafana" @("prometheus", "grafana")
    Start-Stage "Logstash" @("logstash")
    if ($WithKibana) { Start-Stage "Kibana" @("kibana") }
}

Write-Host ""
Write-Host ("Stack is up in {0:N1} min." -f ((Get-Date) - $begin).TotalMinutes) -ForegroundColor Green
Write-Host ""
$idsArgs = $Compose + @("ps", "-q")
$ids = & docker @idsArgs
if ($ids) {
    $statsArgs = @("stats", "--no-stream", "--format", "table {{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}") + $ids
    & docker @statsArgs
}
