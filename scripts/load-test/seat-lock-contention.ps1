<#
.SYNOPSIS
    Proves the Redisson seat lock actually serializes across BOTH ticket-inventory instances.

.DESCRIPTION
    Fires N simultaneous "take this one seat" requests at ticket-inventory-lb and asserts that
    exactly one of them wins. This is the test a single instance cannot provide: with one JVM a
    plain synchronized block passes it just as happily, so the result only means something once
    nginx has spread the attempts over more than one instance -- which is why the X-Upstream
    breakdown below is part of the assertion rather than decoration.

    Why it targets the load balancer (8087) directly rather than api-gateway (8080): the gateway
    adds its own per-route rate limiting, so a burst of N would mix gateway rejections in with the
    422s this test is actually looking for. Going straight at the LB keeps the only concurrency
    control in the path the one under test.

    Expected outcome:
      - exactly 1 response  200  (the winner placed the hold)
      - exactly N-1         422  ("Seats not available" -- DomainException maps to 422, not 409)
      - zero                503  (resilience4j; see -Concurrency for why N stays well under it)
      - at least 2 distinct X-Upstream values, or the run proves nothing about distribution

    The winner's hold survives for inventory.hold.ttl-minutes (10 by default), so the script
    releases it again on the way out, by the winning bookingId -- a reserve-placed hold is stored
    against that, and DELETE /hold would not match it. Without that cleanup a second run against
    the same seat would see zero winners and N failures -- a real result, but not the one being
    measured.

    One customer account is enough, but every attempt must carry its OWN bookingId -- which is
    why this fires at /reserve and not at /hold. Since 570bf87 both hold paths treat a hold the
    same owner already placed as success rather than as a conflict (see SeatHoldPort#holdSeats),
    so twenty /hold calls on one account all return 200 and the run proves nothing about locking.
    /reserve confirms the hold into a bookingId (RedissonSeatHoldAdapter#confirmHold), and twenty
    distinct bookingIds are twenty distinct owners -- exactly one of which can win the key.

    This used to target /hold on the strength of a claim that holdSeats "does not special-case
    the seat's existing owner". 570bf87 made that false and the docs commit four seconds later
    carried the sentence forward unread, so the script went on asserting 1 winner and N-1 losers
    against a path that had started answering 200 to all of them -- a FAIL that said the lock had
    broken when nothing had.

.PARAMETER ShowtimeId
    Showtime to contend over. Find one with:
        Invoke-RestMethod http://localhost:8082/api/v1/matches | ForEach-Object showtimes

.PARAMETER SeatCode
    Seat to fight over, e.g. "A1". Defaults to the first seat the seat map still reports as
    available, so a repeat run picks a fresh seat if an earlier hold is still outstanding.

.PARAMETER Concurrency
    Simultaneous attempts. Default 20. Keep it under 100: that is resilience4j's seat-inventory
    bulkhead maxConcurrentCalls *per instance*, and a shed call never reaches the lock at all --
    it returns 503 without proving anything either way.

.PARAMETER LbUrl
    ticket-inventory-lb as published on the host. 8087, not 8083 -- that host port belongs to
    kafka-connect (see docker-compose.yaml).

.PARAMETER IdentityUrl
    identity-service as published on the host, used only for the login in step 1.

.PARAMETER Email
    Customer account the holds are placed as. Falls back to CONTENTION_TEST_EMAIL; the script
    refuses to start without one, so this and -Password are effectively required.

.PARAMETER Password
    Password for -Email. Falls back to CONTENTION_TEST_PASSWORD. Prefer the environment
    variable: a password passed as an argument lands in the shell's history.

.PARAMETER SkipCleanup
    Leaves the winner's hold in place instead of releasing it on the way out. Useful when the
    next thing you want to look at is a seat that is genuinely held; note that the following
    run then has to pick a different seat (omit -SeatCode and it will).

.NOTES
    X-Upstream carries the container's IP:port rather than its name, since that is all nginx
    knows at that point. To map an address back to an instance:
        docker inspect -f '{{.Name}} {{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' `
            ticket-inventory-service-1_stadium_booking ticket-inventory-service-2_stadium_booking

.EXAMPLE
    ./seat-lock-contention.ps1 -ShowtimeId 8f3c1b2e-... -Email me@example.com -Password secret

.EXAMPLE
    ./seat-lock-contention.ps1 -ShowtimeId 8f3c1b2e-... -SeatCode B7 -Concurrency 50
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ShowtimeId,
    [string]$SeatCode,
    [int]$Concurrency = 20,
    [string]$LbUrl = "http://localhost:8087",
    [string]$IdentityUrl = "http://localhost:8081",
    [string]$Email = $env:CONTENTION_TEST_EMAIL,
    [string]$Password = $env:CONTENTION_TEST_PASSWORD,
    [switch]$SkipCleanup
)

$ErrorActionPreference = "Stop"

if (-not $Email -or -not $Password) {
    throw "Set -Email/-Password, or CONTENTION_TEST_EMAIL/CONTENTION_TEST_PASSWORD in the environment."
}
if ($Concurrency -lt 2) {
    throw "-Concurrency must be at least 2 for there to be any contention."
}

# .NET caps outbound connections per endpoint at 2 by default, which would quietly serialize the
# burst into pairs and leave the lock looking uncontended. This is the single most important line
# in the file.
[System.Net.ServicePointManager]::DefaultConnectionLimit = [Math]::Max(100, $Concurrency * 2)

# ---------------------------------------------------------------------------
# 1. Authenticate.
# ---------------------------------------------------------------------------
Write-Host "Logging in as $Email ..." -ForegroundColor Cyan
$login = Invoke-RestMethod -Method Post -Uri "$IdentityUrl/api/v1/auth/login" `
    -ContentType "application/json" `
    -Body (@{ email = $Email; password = $Password } | ConvertTo-Json)
$token = $login.accessToken
$authHeader = @{ Authorization = "Bearer $token" }

# ---------------------------------------------------------------------------
# 2. Resolve the seat to contend over.
# ---------------------------------------------------------------------------
if (-not $SeatCode) {
    Write-Host "Picking an available seat from the seat map ..." -ForegroundColor Cyan
    $map = Invoke-RestMethod -Method Get -Uri "$LbUrl/api/v1/inventory/$ShowtimeId/seats" -Headers $authHeader
    $free = $map.seats | Where-Object { $_.status -eq "available" } | Select-Object -First 1
    if (-not $free) {
        throw "No available seat left on showtime $ShowtimeId -- every seat is held or sold."
    }
    $SeatCode = $free.code
}
Write-Host "Contending for showtime=$ShowtimeId seat=$SeatCode with $Concurrency simultaneous holds"
Write-Host ""

# ---------------------------------------------------------------------------
# 3. Fire them together.
#    HttpWebRequest rather than Invoke-WebRequest: it reports status, headers and body the same
#    way whether the response was 200 or 422, so the failing majority reads as easily as the
#    winner. Every runspace blocks on one shared gate so the burst really is simultaneous rather
#    than merely concurrent -- with a staggered start the first arrival can finish before the
#    rest reach tryLock, and then the lock is never contended at all.
# ---------------------------------------------------------------------------
$worker = {
    param($Url, $Token, $Body, $Gate, $Index, $BookingId)

    [void]$Gate.Wait(30000)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $status = 0
    $upstream = "(none)"
    $detail = ""

    try {
        $req = [System.Net.HttpWebRequest]::Create($Url)
        $req.Method = "POST"
        $req.ContentType = "application/json"
        $req.Headers.Add("Authorization", "Bearer $Token")
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
        $req.ContentLength = $bytes.Length
        $rs = $req.GetRequestStream()
        $rs.Write($bytes, 0, $bytes.Length)
        $rs.Close()

        $resp = $req.GetResponse()
        $status = [int]$resp.StatusCode
        if ($resp.Headers["X-Upstream"]) { $upstream = $resp.Headers["X-Upstream"] }
        $detail = (New-Object System.IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
        $resp.Close()
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if ($resp) {
            $status = [int]$resp.StatusCode
            if ($resp.Headers["X-Upstream"]) { $upstream = $resp.Headers["X-Upstream"] }
            $detail = (New-Object System.IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
            $resp.Close()
        } else {
            $detail = $_.Exception.Message
        }
    }

    $sw.Stop()
    [pscustomobject]@{
        Index     = $Index
        BookingId = $BookingId
        Status    = $status
        Upstream  = $upstream
        Detail    = $detail
        ElapsedMs = $sw.ElapsedMilliseconds
    }
}

$url = "$LbUrl/api/v1/inventory/$ShowtimeId/reserve"
# Distinct owners are what makes the key contended, so each attempt gets its own bookingId.
# The run id keeps a repeat run from reusing ids a previous run may still hold.
$runId = [guid]::NewGuid().ToString("N").Substring(0, 8)
$gate = New-Object System.Threading.ManualResetEventSlim($false)
$pool = [runspacefactory]::CreateRunspacePool(1, $Concurrency)
$pool.Open()

$pending = @()
foreach ($i in 1..$Concurrency) {
    $bookingId = "loadtest-$runId-$i"
    $body = @{ bookingId = $bookingId; seatCodes = @($SeatCode) } | ConvertTo-Json -Compress
    $ps = [powershell]::Create()
    $ps.RunspacePool = $pool
    [void]$ps.AddScript($worker).
        AddArgument($url).AddArgument($token).AddArgument($body).AddArgument($gate).AddArgument($i).
        AddArgument($bookingId)
    $pending += [pscustomobject]@{ Ps = $ps; Handle = $ps.BeginInvoke() }
}

# Let every runspace spin up and park on the gate before releasing them all at once.
Start-Sleep -Milliseconds 500
$gate.Set()

$results = @()
foreach ($p in $pending) {
    $results += $p.Ps.EndInvoke($p.Handle)
    $p.Ps.Dispose()
}
$pool.Close()
$pool.Dispose()
$gate.Dispose()

# ---------------------------------------------------------------------------
# 4. Report.
# ---------------------------------------------------------------------------
$winners    = @($results | Where-Object { $_.Status -eq 200 })
$rejected   = @($results | Where-Object { $_.Status -eq 422 })
$throttled  = @($results | Where-Object { $_.Status -eq 503 })
$unexpected = @($results | Where-Object { $_.Status -ne 200 -and $_.Status -ne 422 -and $_.Status -ne 503 })

Write-Host "Results"
Write-Host ("  200 winner        : {0}" -f $winners.Count)
Write-Host ("  422 seat taken    : {0}" -f $rejected.Count)
Write-Host ("  503 shed by r4j   : {0}" -f $throttled.Count)
Write-Host ("  other             : {0}" -f $unexpected.Count)
$times = $results | Measure-Object ElapsedMs -Average -Maximum
Write-Host ("  latency avg/max ms: {0:N0} / {1:N0}" -f $times.Average, $times.Maximum)

Write-Host ""
Write-Host "Distribution across instances (X-Upstream)"
$byUpstream = $results | Group-Object Upstream | Sort-Object Count -Descending
$byUpstream | ForEach-Object { Write-Host ("  {0,-24} {1,3} request(s)" -f $_.Name, $_.Count) }

Write-Host ""
Write-Host "Verdict"
$pass = $true

if ($winners.Count -eq 1) {
    Write-Host "  PASS  exactly one hold succeeded" -ForegroundColor Green
} elseif ($winners.Count -eq 0) {
    $pass = $false
    Write-Host "  FAIL  nobody won -- seat $SeatCode was probably still held from an earlier run" -ForegroundColor Red
    Write-Host "        (re-run without -SeatCode to auto-pick a fresh one)" -ForegroundColor Red
} else {
    $pass = $false
    Write-Host ("  FAIL  {0} holds succeeded on the same seat -- the lock did NOT serialize" -f $winners.Count) -ForegroundColor Red
}

if ($winners.Count -eq 1 -and $rejected.Count -eq $Concurrency - 1) {
    Write-Host "  PASS  every loser came back as a clean 422 domain error" -ForegroundColor Green
} else {
    $pass = $false
    Write-Host ("  FAIL  expected {0} losers on 422, got {1}" -f ($Concurrency - 1), $rejected.Count) -ForegroundColor Red
}

if ($throttled.Count -gt 0) {
    Write-Host ("  WARN  {0} call(s) were shed by the bulkhead or rate limiter before reaching the lock." -f $throttled.Count) -ForegroundColor Yellow
    Write-Host "        Lower -Concurrency; those calls prove nothing either way." -ForegroundColor Yellow
}

$distinct = @($byUpstream | Where-Object { $_.Name -ne "(none)" })
if ($distinct.Count -ge 2) {
    Write-Host ("  PASS  attempts really did span {0} instances" -f $distinct.Count) -ForegroundColor Green
} elseif ($distinct.Count -eq 1) {
    $pass = $false
    Write-Host "  FAIL  every attempt landed on ONE instance -- this run says nothing about" -ForegroundColor Red
    Write-Host "        distributed locking, since a JVM-local lock would pass it too." -ForegroundColor Red
} else {
    $pass = $false
    Write-Host "  FAIL  no X-Upstream header came back. Check that add_header X-Upstream is present" -ForegroundColor Red
    Write-Host "        in infra/nginx/ticket-inventory-lb.conf and that the LB was restarted since." -ForegroundColor Red
}

if ($unexpected.Count -gt 0) {
    Write-Host ""
    Write-Host "Unexpected responses" -ForegroundColor Yellow
    $unexpected | Select-Object Index, Status, Upstream, Detail | Format-Table -AutoSize | Out-String | Write-Host
}

# ---------------------------------------------------------------------------
# 5. Release the winner's hold so the next run starts from the same state.
# ---------------------------------------------------------------------------
if (-not $SkipCleanup -and $winners.Count -gt 0) {
    Write-Host ""
    $winningBookingId = $winners[0].BookingId
    Write-Host "Releasing the reservation on $SeatCode ..." -ForegroundColor Cyan
    try {
        Invoke-RestMethod -Method Delete -Headers $authHeader `
            -Uri "$LbUrl/api/v1/inventory/$ShowtimeId/reserve/$($winningBookingId)?seatCodes=$SeatCode" | Out-Null
        Write-Host "Released."
    } catch {
        Write-Host ("Could not release the hold: {0}" -f $_.Exception.Message) -ForegroundColor Yellow
        Write-Host "It expires on its own after inventory.hold.ttl-minutes." -ForegroundColor Yellow
    }
}

if (-not $pass) { exit 1 }
