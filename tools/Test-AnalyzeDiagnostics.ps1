#requires -Version 5.1
<#
.SYNOPSIS
  Regression test for Analyze-Diagnostics.ps1 using a small synthetic case.
  It intentionally includes a clean app cleanup plus an active 25 Hz hub stream,
  so the analyser must not confuse "app cleanup events" with "hardware stopped".
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = Join-Path ([System.IO.Path]::GetTempPath()) ("rawwave-analyzer-test-" + [Guid]::NewGuid().ToString("N"))
$inputDir = Join-Path $root "input"
$outputDir = Join-Path $root "output"
New-Item -ItemType Directory -Path (Join-Path $inputDir "watch") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $inputDir "sessions\s1") -Force | Out-Null

function Write-Json([string]$path, [object]$value) {
    $json = $value | ConvertTo-Json -Depth 12
    [System.IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))
}

$plan = [ordered]@{
    schema = 5
    sessionId = "s1"
    createdUtcMs = 1793000000000
    coreSources = @("PPG")
    dataKinds = [ordered]@{ PPG = "PPG"; ECG = "ECG" }
    template = [ordered]@{ name = "synthetic"; ecg = $true; ppg = $false }
}
$seal = [ordered]@{
    schema = 1
    sessionId = "s1"
    reason = "USER_COMPLETED"
    endedUtcMs = 1793000060000
    fileIntegrity = "SEALED_VERIFIED"
    counts = [ordered]@{ "CORE-1/PPG" = 1500 }
}
Write-Json (Join-Path $inputDir "sessions\s1\plan.json") $plan
Write-Json (Join-Path $inputDir "sessions\s1\seal.json") $seal

$events = @(
    [ordered]@{ schemaVersion = 1; processRunId = "p1"; utc = "2026-11-04T10:00:00Z"; event = "CAPTURE_VENDOR_CLEANUP"; details = [ordered]@{ stage = "TRACKER_CREATED"; sensor = "PPG" } },
    [ordered]@{ schemaVersion = 1; processRunId = "p1"; utc = "2026-11-04T10:01:00Z"; event = "CAPTURE_VENDOR_CLEANUP"; details = [ordered]@{ stage = "FINAL_UNSET_RETURNED"; sensor = "PPG" } },
    [ordered]@{ schemaVersion = 1; processRunId = "p1"; utc = "2026-11-04T10:01:01Z"; event = "CAPTURE_VENDOR_CLEANUP"; details = [ordered]@{ stage = "DISCONNECT_REQUEST"; sensor = "ALL" } },
    [ordered]@{ schemaVersion = 1; processRunId = "p1"; utc = "2026-11-04T10:01:02Z"; event = "CAPTURE_VENDOR_CLEANUP"; details = [ordered]@{ stage = "DISCONNECT_RETURNED"; sensor = "ALL" } },
    [ordered]@{ schemaVersion = 1; processRunId = "p1"; utc = "2026-11-04T10:01:03Z"; event = "CAPTURE_VENDOR_CLEANUP"; details = [ordered]@{ stage = "DISCONNECT_RECHECK"; sensor = "ALL"; detail = "retained=0" } },
    [ordered]@{ schemaVersion = 1; processRunId = "p1"; utc = "2026-11-04T10:01:04Z"; event = "CAPTURE_VENDOR_CLEANUP"; details = [ordered]@{ stage = "OWNER_CLOSED"; sensor = "ALL"; detail = "failure=" } },
    [ordered]@{ schemaVersion = 1; processRunId = "p1"; utc = "2026-11-04T10:01:05Z"; event = "CAPTURE_SESSION_END"; details = [ordered]@{ session = "s1"; detail = "USER_COMPLETED" } },
    [ordered]@{ schemaVersion = 1; processRunId = "p1"; utc = "2026-11-04T10:01:06Z"; event = "ACTIVITY_WATCH_SYNC_FINISHED"; details = [ordered]@{ success = $true; text = "COMMITTED" } }
)
$eventLines = ($events | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 8 }) -join "`n"
[System.IO.File]::WriteAllText((Join-Path $inputDir "watch\events.jsonl"), $eventLines + "`n", (New-Object System.Text.UTF8Encoding($false)))

$observation = [ordered]@{
    schemaVersion = 1
    processRunId = "p1"
    utc = "2026-11-04T10:02:00Z"
    event = "DIAGNOSTIC_CAPTURE"
    details = [ordered]@{
        diagnosticCaptureId = "capture-1"
        symptoms = @("LED_PERSISTENT")
        led = "ON"
        watchExit = "NOT_EXITED"
        dataArrival = "NOT_OBSERVED"
        rebootState = "NOT_RECORDED"
    }
}
[System.IO.File]::WriteAllText((Join-Path $inputDir "watch\diagnostic-observations.jsonl"),
    (($observation | ConvertTo-Json -Compress -Depth 8) + "`n"), (New-Object System.Text.UTF8Encoding($false)))

$dump = @"
DUMP OF SERVICE CRITICAL sensorservice:
Active sensors:
offbody_detect  Wakeup (handle=0x1, connections=1)
Socket Buffer size = 10 events
WakeLock Status: not held
DUMP OF SERVICE CRITICAL activity:
  * ServiceRecord{abc u0 com.samsung.android.wear.shealth/.service.HealthSensorService c:com.samsung.android.wear.shealth}
    packageName=com.samsung.android.wear.shealth
    processName=com.samsung.android.wear.shealth
    isForeground=true startRequested=true
  * ServiceRecord{def u0 com.rawwave.rebuild/com.rawwave.shell.SyncGuardService c:com.rawwave.rebuild}
    packageName=com.rawwave.rebuild
    processName=com.rawwave.rebuild
    startRequested=false
DUMP OF SERVICE CRITICAL cpuinfo:
------ PROCESSES AND THREADS (ps -A -T -Z -O pri,nice,rtprio,sched,pcy,time) ------
u:r:untrusted_app:s0 u0_a206 29821 29821 1 1 1 0 0 S 19 0 - 0 bg 00:00:01 rawwave.rebuild
u:r:platform_app:s0 u0_a33 30021 30021 1 1 1 0 0 S 19 0 - 0 bg 00:00:01 .service.health
------ SYSTEM LOG (logcat -v threadtime -d *:v) ------
11-04 10:00:00.000 1000 1000 1000 I ActivityManager: Start proc 30021:com.samsung.android.service.health for bound-service com.samsung.android.service.health/.tracking.requesthandler.WearTrackerService
------ EVENT LOG (logcat -b events -v threadtime -d *:v) ------
"@
[System.IO.File]::WriteAllText((Join-Path $inputDir "dumpstate.txt"), $dump, (New-Object System.Text.UTF8Encoding($false)))

$nano = @"
log created time - Wed Nov 4 10:00:00 2026
[100.000000] [lsw]sns_ppg_multi_25hz_inst_set_client_config - g:1,r:1,ir:1
[100.010000] [lsw]ppg_multi_25hz_request_set_led - value:7
[101.000000] [ppg_multi_25hz]v:1/1/1|ppg:1/2/3|acc:0/0/0
[102.000000] [ppg_multi_25hz]v:1/1/1|ppg:1/2/3|acc:0/0/0
"@
[System.IO.File]::WriteAllText((Join-Path $inputDir "nanohub-log-0.txt"), $nano, (New-Object System.Text.UTF8Encoding($false)))

$analyzer = Join-Path $PSScriptRoot "Analyze-Diagnostics.ps1"
& $analyzer -InputPath $inputDir -OutputPath $outputDir -CaptureId "capture-1"
if (-not (Test-Path (Join-Path $outputDir "analysis.json"))) { throw "analysis.json was not produced" }
$result = Get-Content (Join-Path $outputDir "analysis.json") -Raw -Encoding UTF8 | ConvertFrom-Json

function Assert-Equal($expected, $actual, [string]$message) {
    if ($expected -ne $actual) { throw "$message (expected=$expected actual=$actual)" }
}
function Assert-True($condition, [string]$message) {
    if (-not $condition) { throw $message }
}

Assert-Equal "VENDOR_SENSOR_HUB_OR_FIRMWARE" $result.classification "classification"
Assert-Equal "CAPTURE_ID" $result.correlation.mode "correlation mode"
Assert-Equal "capture-1" $result.correlation.requestedCaptureId "capture id"
Assert-True ($result.hub.active25 -eq $true) "25 Hz stream should be active at log end"
Assert-True (@($result.findings | Where-Object { $_.code -eq "PPG_25HZ_STREAM_ACTIVE_AT_LOG_END" }).Count -eq 1) "hub finding missing"
Assert-True (@($result.findings | Where-Object { $_.code -eq "APP_TRACKER_UNSET_EVENTS_PRESENT" }).Count -eq 1) "app cleanup evidence missing"
Assert-Equal "STREAM_ACTIVE_AT_LOG_END" $result.layers.L6_SENSOR_HUB.status "L6 status"
Assert-Equal "USER_REPORTED" $result.layers.L0_USER_UI.status "L0 status"
Assert-Equal 1 @($result.observations).Count "observation count"
Assert-Equal "ON" $result.observations[0].led "observation LED"

Remove-Item -LiteralPath $root -Recurse -Force
Write-Host "ANALYZE_DIAGNOSTICS_REGRESSION_PASS"
