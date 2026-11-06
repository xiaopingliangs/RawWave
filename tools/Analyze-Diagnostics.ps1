#requires -Version 5.1
<#
.SYNOPSIS
  Local, read-only analyser for RawWave diagnostic evidence.

.DESCRIPTION
  Accepts a directory or ZIP containing any combination of:
    - RawWave activity ZIPs (plan.json / seal.json / raw blocks)
    - phone or watch problem reports (bundle.json + events.jsonl)
    - Android/Wear system bugreports (dumpstate + extralog zips)
    - extracted nanohub-log-*.txt files
    - new diagnostic-manifest.json / postconditions.json / diagnostic-observations.jsonl

  It produces analysis.json, analysis.md and analysis-summary.txt with a
  layer-by-layer conclusion. It never claims that app cleanup proves hardware
  stopped, and it never treats a failed test prerequisite as a product result.

.EXAMPLE
  .\Analyze-Diagnostics.ps1 -InputPath .\evidence\20261104-led
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,
    [string]$OutputPath = "",
    [string]$ProductPackage = "com.rawwave.rebuild",
    [string]$CaptureId = "",
    [string]$WindowStartUtc = "",
    [string]$WindowEndUtc = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

$script:work = Join-Path ([System.IO.Path]::GetTempPath()) ("rawwave-analyze-" + [Guid]::NewGuid().ToString("N"))
$script:zipIndex = 0
New-Item -ItemType Directory -Path $script:work -Force | Out-Null

function Write-Step([string]$text) {
    Write-Host "[Analyze-Diagnostics] $text"
}

function Expand-ZipSafe([string]$zip, [string]$destination) {
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $tar = Get-Command tar.exe -ErrorAction SilentlyContinue
    if ($null -ne $tar) {
        $process = Start-Process -FilePath $tar.Source -ArgumentList @("-xf", $zip, "-C", $destination) -NoNewWindow -Wait -PassThru
        if ($process.ExitCode -eq 0) { return }
    }
    try {
        Expand-Archive -LiteralPath $zip -DestinationPath $destination -Force
    } catch {
        throw "ZIP_EXTRACT_FAILED: $zip :: $($_.Exception.Message)"
    }
}

function Get-JsonFile([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try {
        $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
        if ([string]::IsNullOrWhiteSpace($text)) { return $null }
        return $text | ConvertFrom-Json
    } catch {
        return $null
    }
}

function Get-JsonLines([string]$path) {
    $result = New-Object System.Collections.Generic.List[object]
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $result }
    $corrupt = 0
    foreach ($line in [System.IO.File]::ReadLines($path, [System.Text.Encoding]::UTF8)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try {
            $value = $line | ConvertFrom-Json
            if ($null -ne $value) { $result.Add($value) }
        } catch {
            $corrupt++
        }
    }
    return [pscustomobject]@{ events = $result; corrupt = $corrupt }
}

function Get-Detail([object]$event, [string]$name) {
    if ($null -eq $event -or $null -eq $event.details) { return $null }
    $property = $event.details.PSObject.Properties[$name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-Field([object]$object, [string]$name) {
    if ($null -eq $object) { return $null }
    $property = $object.PSObject.Properties[$name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function As-String([object]$value) {
    if ($null -eq $value) { return "" }
    if ($value -is [array]) { return ($value -join ",") }
    return [string]$value
}

function Add-Finding {
    param(
        [System.Collections.Generic.List[object]]$Findings,
        [string]$Code,
        [string]$Layer,
        [string]$Severity,
        [string]$Confidence,
        [string]$Summary,
        [string[]]$Evidence
    )
    $Findings.Add([ordered]@{
        code = $Code
        layer = $Layer
        severity = $Severity
        confidence = $Confidence
        summary = $Summary
        evidence = @($Evidence)
    })
}

function Set-Layer {
    param(
        [hashtable]$Layers,
        [string]$Name,
        [string]$Status,
        [string]$Evidence,
        [hashtable]$Extra = @{}
    )
    $item = [ordered]@{
        status = $Status
        evidence = $Evidence
        provenBy = @()
        nonClaims = @()
    }
    foreach ($key in $Extra.Keys) { $item[$key] = $Extra[$key] }
    $Layers[$Name] = $item
}

function Get-MaxNullable([object]$a, [object]$b) {
    if ($null -eq $a) { return $b }
    if ($null -eq $b) { return $a }
    return [Math]::Max([double]$a, [double]$b)
}

# ---------------------------------------------------------------------------
# 1. Expand inputs
# ---------------------------------------------------------------------------
$inputResolved = (Resolve-Path -LiteralPath $InputPath).Path
$roots = New-Object System.Collections.Generic.List[string]
if (Test-Path -LiteralPath $inputResolved -PathType Container) {
    $roots.Add($inputResolved)
} else {
    $firstExtract = Join-Path $script:work "input-0"
    Expand-ZipSafe $inputResolved $firstExtract
    $roots.Add($firstExtract)
}

$processedZips = @{}
for ($depth = 0; $depth -lt 4; $depth++) {
    $zipFiles = New-Object System.Collections.Generic.List[System.IO.FileInfo]
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root -PathType Container)) { continue }
        Get-ChildItem -LiteralPath $root -Recurse -File -Filter "*.zip" -ErrorAction SilentlyContinue |
            ForEach-Object { $zipFiles.Add($_) }
    }
    $fresh = @($zipFiles | Where-Object { -not $processedZips.ContainsKey($_.FullName) })
    if ($fresh.Count -eq 0) { break }
    foreach ($zip in $fresh) {
        $processedZips[$zip.FullName] = $true
        $destination = Join-Path $script:work ("zip-" + $script:zipIndex)
        $script:zipIndex++
        try {
            Write-Step "extract $($zip.Name)"
            Expand-ZipSafe $zip.FullName $destination
            $roots.Add($destination)
        } catch {
            Write-Warning "Could not extract $($zip.FullName): $($_.Exception.Message)"
        }
    }
}

$allFiles = New-Object System.Collections.Generic.List[System.IO.FileInfo]
foreach ($root in $roots) {
    Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue |
        ForEach-Object { $allFiles.Add($_) }
}
$uniqueFiles = @($allFiles | Sort-Object FullName -Unique)

# ---------------------------------------------------------------------------
# 2. Classify files
# ---------------------------------------------------------------------------
$sessionDirs = New-Object System.Collections.Generic.List[string]
$eventsFiles = New-Object System.Collections.Generic.List[object]
$observationFiles = New-Object System.Collections.Generic.List[object]
$buildFiles = New-Object System.Collections.Generic.List[object]
$dumpFiles = New-Object System.Collections.Generic.List[System.IO.FileInfo]
$nanoFiles = New-Object System.Collections.Generic.List[System.IO.FileInfo]
$manifestFiles = New-Object System.Collections.Generic.List[System.IO.FileInfo]

foreach ($file in $uniqueFiles) {
    $name = $file.Name
    if ($name -eq "plan.json" -and (Test-Path -LiteralPath (Join-Path $file.DirectoryName "seal.json"))) {
        $sessionDirs.Add($file.DirectoryName)
        continue
    }
    if ($name -eq "events.jsonl" -or $name -eq "events.previous.jsonl") {
        $role = ""
        if ($file.FullName -match "[\\/]watch[\\/]") { $role = "watch" }
        elseif ($file.FullName -match "[\\/]phone[\\/]") { $role = "phone" }
        $eventsFiles.Add([pscustomobject]@{ file = $file; role = $role; name = $name })
        continue
    }
    if ($name -eq "diagnostic-observations.jsonl" -or $name -eq "diagnostic-observations.previous.jsonl") {
        $role = ""
        if ($file.FullName -match "[\\/]watch[\\/]") { $role = "watch" }
        elseif ($file.FullName -match "[\\/]phone[\\/]") { $role = "phone" }
        $observationFiles.Add([pscustomobject]@{ file = $file; role = $role; name = $name })
        continue
    }
    if ($name -eq "build.json") { $buildFiles.Add($file); continue }
    if ($name -eq "diagnostic-manifest.json" -or $name -eq "postconditions.json") { $manifestFiles.Add($file); continue }
    if ($name -match "^dumpstate.*\.txt$" -or $name -match "^bugreport.*\.txt$") { $dumpFiles.Add($file); continue }
    if ($name -match "^nanohub-log-\d+\.txt$") { $nanoFiles.Add($file); continue }
}

# ---------------------------------------------------------------------------
# 3. Activity sessions
# ---------------------------------------------------------------------------
$sessions = New-Object System.Collections.Generic.List[object]
foreach ($dir in ($sessionDirs | Sort-Object -Unique)) {
    $plan = Get-JsonFile (Join-Path $dir "plan.json")
    $seal = Get-JsonFile (Join-Path $dir "seal.json")
    if ($null -eq $plan) { continue }
    $counts = [ordered]@{}
    $totalRows = [int64]0
    $countObject = Get-Field $seal "counts"
    if ($null -ne $countObject) {
        foreach ($property in $countObject.PSObject.Properties) {
            $counts[$property.Name] = [int64]$property.Value
            $totalRows += [int64]$property.Value
        }
    }
    $createdUtcMs = [int64](Get-Field $plan "createdUtcMs")
    $endedUtcMs = [int64](Get-Field $seal "endedUtcMs")
    $reason = As-String (Get-Field $seal "reason")
    $integrity = As-String (Get-Field $seal "fileIntegrity")
    $template = Get-Field $plan "template"
    $templateName = As-String (Get-Field $template "name")
    $coreSources = @((Get-Field $plan "coreSources") | ForEach-Object { As-String $_ })
    $dataKinds = @()
    $kindObject = Get-Field $plan "dataKinds"
    if ($null -ne $kindObject) { $dataKinds = @($kindObject.PSObject.Properties.Name) }
    $eventOnly = ($counts.Count -gt 0)
    foreach ($key in $counts.Keys) { if ($key -notmatch "/EVENT$") { $eventOnly = $false } }
    $hasCore = $false
    $hasEcg = $false
    $hasCumulativePpg = $false
    foreach ($key in $counts.Keys) {
        if ($key -match "^CORE-") { $hasCore = $true }
        if ($key -match "/ECG$") { $hasEcg = $true }
        if ($key -match "/PPG$" -or $key -match "/PPG_ON_DEMAND$") { $hasCumulativePpg = $true }
    }
    $session = [ordered]@{
        sessionId = As-String (Get-Field $plan "sessionId")
        folder = $dir
        template = $templateName
        createdAtUtc = if ($createdUtcMs -gt 0) { [DateTimeOffset]::FromUnixTimeMilliseconds($createdUtcMs).UtcDateTime.ToString("o") } else { "" }
        endedAtUtc = if ($endedUtcMs -gt 0) { [DateTimeOffset]::FromUnixTimeMilliseconds($endedUtcMs).UtcDateTime.ToString("o") } else { "" }
        sealReason = $reason
        integrity = $integrity
        counts = $counts
        totalRows = $totalRows
        coreSources = $coreSources
        dataKinds = $dataKinds
        eventOnly = $eventOnly
        hasCore = $hasCore
        hasEcg = $hasEcg
        hasPpg = $hasCumulativePpg
    }
    $sessions.Add([pscustomobject]$session)
}

# ---------------------------------------------------------------------------
# 4. App reports
# ---------------------------------------------------------------------------
$appEvents = New-Object System.Collections.Generic.List[object]
$appEventCorrupt = 0
$appEventFiles = New-Object System.Collections.Generic.List[string]
foreach ($entry in $eventsFiles) {
    $parsed = Get-JsonLines $entry.file.FullName
    $appEventCorrupt += $parsed.corrupt
    $appEventFiles.Add($entry.file.FullName)
    foreach ($event in $parsed.events) {
        $event | Add-Member -NotePropertyName "__file" -NotePropertyValue $entry.file.FullName -Force
        $event | Add-Member -NotePropertyName "__role" -NotePropertyValue $entry.role -Force
        $appEvents.Add($event)
    }
}
$appEvents = @($appEvents | Sort-Object { As-String (Get-Field $_ "utc") })

$observations = New-Object System.Collections.Generic.List[object]
$observationCorrupt = 0
foreach ($entry in $observationFiles) {
    $parsed = Get-JsonLines $entry.file.FullName
    $observationCorrupt += $parsed.corrupt
    foreach ($event in $parsed.events) {
        $event | Add-Member -NotePropertyName "__role" -NotePropertyValue $entry.role -Force
        $observations.Add($event)
    }
}
$observations = @($observations | Sort-Object { As-String (Get-Field $_ "utc") })

$buildIdentities = New-Object System.Collections.Generic.List[object]
foreach ($file in $buildFiles) {
    $build = Get-JsonFile $file.FullName
    if ($null -ne $build) {
        $buildIdentities.Add([pscustomobject]@{ file = $file.FullName; build = $build })
    }
}

# Cleanup ledger from CAPTURE_VENDOR_CLEANUP events. Group by ownerId so an older
# successful cleanup cannot hide a newer leak.
$cleanupOwners = @{}
$sessionEndDetail = ""
$persistenceDetail = ""
$syncSuccess = $null
$syncText = ""
$exitRequested = $false
$sdkCapability = $null
$lastEventUtc = ""
$processRunIds = New-Object System.Collections.Generic.HashSet[string]
$sessionIds = New-Object System.Collections.Generic.HashSet[string]

foreach ($event in $appEvents) {
    $kind = As-String (Get-Field $event "event")
    $utc = As-String (Get-Field $event "utc")
    if (-not [string]::IsNullOrWhiteSpace($utc)) { $lastEventUtc = $utc }
    $runId = As-String (Get-Field $event "processRunId")
    if (-not [string]::IsNullOrWhiteSpace($runId)) { [void]$processRunIds.Add($runId) }
    $sessionId = As-String (Get-Detail $event "session")
    if ([string]::IsNullOrWhiteSpace($sessionId)) { $sessionId = As-String (Get-Detail $event "sessionId") }
    if (-not [string]::IsNullOrWhiteSpace($sessionId) -and $sessionId -ne "none") { [void]$sessionIds.Add($sessionId) }
    if ($kind -eq "CAPTURE_VENDOR_CLEANUP") {
        $ownerId = As-String (Get-Detail $event "ownerId")
        if ([string]::IsNullOrWhiteSpace($ownerId)) { $ownerId = "LEGACY" }
        if (-not $cleanupOwners.ContainsKey($ownerId)) {
            $cleanupOwners[$ownerId] = [ordered]@{
                ownerId = $ownerId
                lastSeq = [int64]0
                firstUtc = ""
                lastUtc = ""
                created = New-Object System.Collections.Generic.HashSet[string]
                finalReturned = New-Object System.Collections.Generic.HashSet[string]
                finalFailed = New-Object System.Collections.Generic.HashSet[string]
                intermediateFailed = New-Object System.Collections.Generic.HashSet[string]
                disconnectRequested = $false
                disconnectReturned = $false
                disconnectFailed = $false
                recheckDetail = ""
                ownerClosed = $false
                ownerClosedDetail = ""
            }
        }
        $ledger = $cleanupOwners[$ownerId]
        $seq = [int64](Get-Field $event "eventSeq")
        if ($seq -gt [int64]$ledger.lastSeq) { $ledger.lastSeq = $seq }
        if (-not [string]::IsNullOrWhiteSpace($utc)) {
            if ([string]::IsNullOrWhiteSpace($ledger.firstUtc) -or $utc -lt $ledger.firstUtc) { $ledger.firstUtc = $utc }
            if ([string]::IsNullOrWhiteSpace($ledger.lastUtc) -or $utc -gt $ledger.lastUtc) { $ledger.lastUtc = $utc }
        }
        $stage = As-String (Get-Detail $event "stage")
        $sensor = As-String (Get-Detail $event "sensor")
        if ($stage -eq "TRACKER_CREATED" -and $sensor -ne "") { [void]$ledger.created.Add($sensor) }
        if ($stage -eq "FINAL_UNSET_RETURNED" -and $sensor -ne "") { [void]$ledger.finalReturned.Add($sensor) }
        if (($stage -eq "FINAL_UNSET_FAILED" -or $stage -eq "FINAL_UNSET_RETRY_FAILED") -and $sensor -ne "") { [void]$ledger.finalFailed.Add($sensor) }
        if ($stage -eq "UNSET_FAILED" -and $sensor -ne "") { [void]$ledger.intermediateFailed.Add($sensor) }
        if ($stage -eq "DISCONNECT_REQUEST") { $ledger.disconnectRequested = $true }
        if ($stage -eq "DISCONNECT_RETURNED") { $ledger.disconnectReturned = $true }
        if ($stage -eq "DISCONNECT_FAILED") { $ledger.disconnectFailed = $true }
        if ($stage -eq "DISCONNECT_RECHECK") { $ledger.recheckDetail = As-String (Get-Detail $event "detail") }
        if ($stage -eq "OWNER_CLOSED") { $ledger.ownerClosed = $true; $ledger.ownerClosedDetail = As-String (Get-Detail $event "detail") }
    }
    if ($kind -eq "CAPTURE_SESSION_END") { $sessionEndDetail = As-String (Get-Detail $event "detail") }
    if ($kind -eq "CAPTURE_PERSISTENCE_SUMMARY") { $persistenceDetail = As-String (Get-Detail $event "detail") }
    if ($kind -eq "ACTIVITY_WATCH_SYNC_FINISHED") {
        $successValue = Get-Detail $event "success"
        if ($null -ne $successValue) { $syncSuccess = [bool]$successValue }
        $syncText = As-String (Get-Detail $event "text")
    }
    if ($kind -eq "EXIT_REQUESTED") { $exitRequested = $true }
    if ($kind -eq "SDK_CAPABILITY") { $sdkCapability = $event.details }
}

$referenceUtc = $null
$correlationMode = "LATEST_OWNER"
if (-not [string]::IsNullOrWhiteSpace($CaptureId)) {
    foreach ($event in $observations) {
        $details = Get-Field $event "details"
        if ($null -eq $details) { continue }
        if ((As-String (Get-Field $details "diagnosticCaptureId")) -eq $CaptureId) {
            $referenceUtc = As-String (Get-Field $event "utc")
            $correlationMode = "CAPTURE_ID"
            break
        }
    }
}
if ($null -eq $referenceUtc -and -not [string]::IsNullOrWhiteSpace($WindowEndUtc)) {
    $referenceUtc = $WindowEndUtc
    $correlationMode = "WINDOW_END"
}
$candidateLedgers = @($cleanupOwners.Values | Where-Object { $_.created.Count -gt 0 })
if ($candidateLedgers.Count -eq 0) { $candidateLedgers = @($cleanupOwners.Values) }
$windowed = $candidateLedgers
if (-not [string]::IsNullOrWhiteSpace($WindowStartUtc) -or -not [string]::IsNullOrWhiteSpace($WindowEndUtc)) {
    $windowed = @($candidateLedgers | Where-Object {
        $inside = $true
        if (-not [string]::IsNullOrWhiteSpace($WindowStartUtc) -and $_.lastUtc -lt $WindowStartUtc) { $inside = $false }
        if (-not [string]::IsNullOrWhiteSpace($WindowEndUtc) -and $_.firstUtc -gt $WindowEndUtc) { $inside = $false }
        $inside
    })
    if ($windowed.Count -eq 0) { $windowed = $candidateLedgers }
}
$selectedLedger = $null
if ($null -ne $referenceUtc) {
    $before = @($windowed | Where-Object { $_.lastUtc -le $referenceUtc })
    if ($before.Count -gt 0) {
        $selectedLedger = $before | Sort-Object { $_.lastUtc } | Select-Object -Last 1
    } else {
        $selectedLedger = $windowed | Sort-Object { $_.lastUtc } | Select-Object -First 1
    }
} else {
    $selectedLedger = $windowed | Sort-Object { [int64]$_.lastSeq } | Select-Object -Last 1
}
if ($null -eq $selectedLedger) {
    $createdSensors = New-Object System.Collections.Generic.HashSet[string]
    $finalReturned = New-Object System.Collections.Generic.HashSet[string]
    $finalFailed = New-Object System.Collections.Generic.HashSet[string]
    $intermediateFailed = New-Object System.Collections.Generic.HashSet[string]
    $disconnectRequested = $false
    $disconnectReturned = $false
    $disconnectFailed = $false
    $recheckDetail = ""
    $ownerClosedDetail = ""
    $ownerClosedSeen = $false
} else {
    $createdSensors = $selectedLedger.created
    $finalReturned = $selectedLedger.finalReturned
    $finalFailed = $selectedLedger.finalFailed
    $intermediateFailed = $selectedLedger.intermediateFailed
    $disconnectRequested = $selectedLedger.disconnectRequested
    $disconnectReturned = $selectedLedger.disconnectReturned
    $disconnectFailed = $selectedLedger.disconnectFailed
    $recheckDetail = $selectedLedger.recheckDetail
    $ownerClosedDetail = $selectedLedger.ownerClosedDetail
    $ownerClosedSeen = $selectedLedger.ownerClosed
}
$cleanupOwnerSummaries = @()
foreach ($ledger in $cleanupOwners.Values) {
    $missingForOwner = @($ledger.created | Where-Object { -not $ledger.finalReturned.Contains($_) } | ForEach-Object { $_ })
    $cleanupOwnerSummaries += [ordered]@{
        ownerId = $ledger.ownerId
        lastEventSeq = $ledger.lastSeq
        firstUtc = $ledger.firstUtc
        lastUtc = $ledger.lastUtc
        created = @($ledger.created | ForEach-Object { $_ })
        finalUnsetReturned = @($ledger.finalReturned | ForEach-Object { $_ })
        finalUnsetFailed = @($ledger.finalFailed | ForEach-Object { $_ })
        missingFinalUnset = $missingForOwner
        disconnectReturned = $ledger.disconnectReturned
        disconnectFailed = $ledger.disconnectFailed
        ownerClosed = $ledger.ownerClosed
        clean = ($missingForOwner.Count -eq 0 -and $ledger.finalFailed.Count -eq 0 -and -not $ledger.disconnectFailed)
    }
}

# ---------------------------------------------------------------------------
# 5. System dump
# ---------------------------------------------------------------------------
$dumpEvidence = [ordered]@{
    available = $false
    file = ""
    generatedHint = ""
    sensorserviceLines = @()
    activityBlocks = @()
    psLines = @()
    logcatLines = @()
}
$healthServiceBlock = $null
$wearTrackerBlock = $null
$rawWaveServiceBlocks = @()
$sensorActiveLines = @()
$sensorActivePpg = $false
$sensorActiveRawWave = $false
$sensorRawWaveHistory = $false
$psHealth = @()
$psWearHealth = @()
$psRawWave = @()

if ($dumpFiles.Count -gt 0) {
    $dumpFile = $dumpFiles | Sort-Object Length -Descending | Select-Object -First 1
    $dumpEvidence.available = $true
    $dumpEvidence.file = $dumpFile.FullName
    $current = ""
    $activityBlock = New-Object System.Collections.Generic.List[string]
    $activityBlocks = New-Object System.Collections.Generic.List[object]
    $sensorLines = New-Object System.Collections.Generic.List[string]
    $psLines = New-Object System.Collections.Generic.List[string]
    $logLines = New-Object System.Collections.Generic.List[string]
    $inActiveSensors = $false
    $inSensorSection = $false

    foreach ($line in [System.IO.File]::ReadLines($dumpFile.FullName)) {
        if ($line -match "^DUMP OF SERVICE CRITICAL sensorservice:") {
            if ($current -eq "activity" -and $activityBlock.Count -gt 0) {
                $activityBlocks.Add(($activityBlock -join "`n")); $activityBlock.Clear()
            }
            $current = "sensorservice"; $inSensorSection = $true; $inActiveSensors = $false
            continue
        }
        if ($line -match "^DUMP OF SERVICE (CRITICAL )?activity:") {
            if ($current -eq "activity" -and $activityBlock.Count -gt 0) {
                $activityBlocks.Add(($activityBlock -join "`n")); $activityBlock.Clear()
            }
            $current = "activity"; $inSensorSection = $false; $inActiveSensors = $false
            continue
        }
        if ($line -match "^DUMP OF SERVICE ") {
            if ($current -eq "activity" -and $activityBlock.Count -gt 0) {
                $activityBlocks.Add(($activityBlock -join "`n")); $activityBlock.Clear()
            }
            $current = ""; $inSensorSection = $false; $inActiveSensors = $false
            continue
        }
        if ($line -match "^------ PROCESSES AND THREADS") { $current = "ps"; $inSensorSection = $false; $inActiveSensors = $false; continue }
        if ($line -match "^------ SYSTEM LOG") { $current = "logcat"; $inSensorSection = $false; $inActiveSensors = $false; continue }
        if ($line -match "^------ " -and $current -ne "activity" -and $current -ne "logcat") {
            $current = ""; $inSensorSection = $false; $inActiveSensors = $false
            continue
        }
        if ($current -eq "sensorservice") {
            $sensorLines.Add($line)
            if ($line -match "^Active sensors:") { $inActiveSensors = $true; continue }
            if ($inActiveSensors) {
                if ($line -match "^(Socket Buffer size|WakeLock Status|Mode\s*:|Sensor Privacy|$)") { $inActiveSensors = $false }
                else { $sensorActiveLines += $line }
            }
            if ($line -match "rawwave") { $sensorRawWaveHistory = $true }
            continue
        }
        if ($current -eq "activity") {
            if ($line -match "^\s*\* ServiceRecord\{") {
                if ($activityBlock.Count -gt 0) {
                    $activityBlocks.Add(($activityBlock -join "`n")); $activityBlock.Clear()
                }
                $activityBlock.Add($line)
            } elseif ($activityBlock.Count -gt 0) {
                $activityBlock.Add($line)
            }
            continue
        }
        if ($current -eq "ps") {
            if ($line -match "rawwave|service\.health|wear\.shealth") { $psLines.Add($line) }
            continue
        }
        if ($current -eq "logcat") {
            if ($line -match "rawwave|WearTrackerService|HealthSensorService|service\.health|wear\.shealth|sensorservice|nanohub|ppg_multi|HealthTracker|FATAL EXCEPTION|ANR in") {
                $logLines.Add($line)
            }
            continue
        }
    }
    if ($current -eq "activity" -and $activityBlock.Count -gt 0) {
        $activityBlocks.Add(($activityBlock -join "`n"))
    }
    $dumpEvidence.sensorserviceLines = $sensorLines.ToArray()
    $dumpEvidence.activityBlocks = $activityBlocks.ToArray()
    $dumpEvidence.psLines = $psLines.ToArray()
    $dumpEvidence.logcatLines = $logLines.ToArray()
    $sensorActivePpg = [bool](@($sensorActiveLines | Where-Object { $_ -match "PPG|ppg" }).Count -gt 0)
    $sensorActiveRawWave = [bool](@($sensorActiveLines | Where-Object { $_ -match "rawwave" }).Count -gt 0)
    foreach ($block in $activityBlocks) {
        if ($block -match "HealthSensorService" -and $null -eq $healthServiceBlock) { $healthServiceBlock = $block }
        if ($block -match "WearTrackerService" -and $null -eq $wearTrackerBlock) { $wearTrackerBlock = $block }
        if ($block -match "rawwave") { $rawWaveServiceBlocks += $block }
    }
    $psHealth = @($psLines | Where-Object { $_ -match "service\.health" })
    $psWearHealth = @($psLines | Where-Object { $_ -match "wear\.shealth" })
    $psRawWave = @($psLines | Where-Object { $_ -match "rawwave" })
}

# ---------------------------------------------------------------------------
# 6. Nanohub logs
# ---------------------------------------------------------------------------
$hub = [ordered]@{
    available = $false
    fileCount = 0
    firstSec = $null
    lastSec = $null
    samples25 = 0
    samples100 = 0
    activeSamples25 = 0
    activeSamples100 = 0
    lastSample25 = $null
    lastSample100 = $null
    lastStop25 = $null
    lastStop100 = $null
    lastStart25 = $null
    lastStart100 = $null
    lastColor25 = ""
    lastColor100 = ""
    lastLed25 = $null
    lastLed100 = $null
    active25 = $false
    active100 = $false
}
$hubLastSample25 = $null
$hubLastSample100 = $null
$hubLastStop25 = $null
$hubLastStop100 = $null
$hubLastStart25 = $null
$hubLastStart100 = $null
$hubLastLed25 = $null
$hubLastLed100 = $null
$hubLastColor25 = ""
$hubLastColor100 = ""
$hubSamples25 = 0
$hubSamples100 = 0
$hubActiveSamples25 = 0
$hubActiveSamples100 = 0

if ($nanoFiles.Count -gt 0) {
    $hub.available = $true
    $hub.fileCount = $nanoFiles.Count
    foreach ($file in $nanoFiles) {
        foreach ($line in [System.IO.File]::ReadLines($file.FullName)) {
            $match = [regex]::Match($line, "^\[(?<ts>\d+(?:\.\d+)?)\]\s+(?<rest>.*)$")
            if (-not $match.Success) { continue }
            $timestamp = [double]$match.Groups["ts"].Value
            $rest = $match.Groups["rest"].Value
            if ($null -eq $hub.firstSec -or $timestamp -lt $hub.firstSec) { $hub.firstSec = $timestamp }
            if ($null -eq $hub.lastSec -or $timestamp -gt $hub.lastSec) { $hub.lastSec = $timestamp }

            $sample25 = [regex]::Match($rest, "\[ppg_multi_25hz\]v:(?<g>\d)/(?<r>\d)/(?<ir>\d)")
            if ($sample25.Success) {
                $hubSamples25++
                $color = "$($sample25.Groups['g'].Value)/$($sample25.Groups['r'].Value)/$($sample25.Groups['ir'].Value)"
                if ($sample25.Groups["g"].Value -ne "0" -or $sample25.Groups["r"].Value -ne "0" -or $sample25.Groups["ir"].Value -ne "0") {
                    $hubActiveSamples25++
                    $hubLastColor25 = $color
                    if ($null -eq $hubLastSample25 -or $timestamp -gt $hubLastSample25) { $hubLastSample25 = $timestamp }
                }
                continue
            }
            $sample100 = [regex]::Match($rest, "\[ppg_multi_100hz\]v:(?<g>\d)/(?<r>\d)/(?<ir>\d)")
            if ($sample100.Success) {
                $hubSamples100++
                $color = "$($sample100.Groups['g'].Value)/$($sample100.Groups['r'].Value)/$($sample100.Groups['ir'].Value)"
                if ($sample100.Groups["g"].Value -ne "0" -or $sample100.Groups["r"].Value -ne "0" -or $sample100.Groups["ir"].Value -ne "0") {
                    $hubActiveSamples100++
                    $hubLastColor100 = $color
                    if ($null -eq $hubLastSample100 -or $timestamp -gt $hubLastSample100) { $hubLastSample100 = $timestamp }
                }
                continue
            }
            $config25 = [regex]::Match($rest, "sns_ppg_multi_25hz_inst_set_client_config\s*-\s*g:(?<g>\d),r:(?<r>\d),ir:(?<ir>\d)")
            if ($config25.Success) {
                if ($config25.Groups["g"].Value -eq "0" -and $config25.Groups["r"].Value -eq "0" -and $config25.Groups["ir"].Value -eq "0") {
                    if ($null -eq $hubLastStop25 -or $timestamp -gt $hubLastStop25) { $hubLastStop25 = $timestamp }
                } else {
                    if ($null -eq $hubLastStart25 -or $timestamp -gt $hubLastStart25) { $hubLastStart25 = $timestamp }
                }
            }
            if ($rest -match "sns_ppg_multi_25hz_set_client_request\s*-\s*rm:1") {
                if ($null -eq $hubLastStop25 -or $timestamp -gt $hubLastStop25) { $hubLastStop25 = $timestamp }
            }
            $config100 = [regex]::Match($rest, "sns_ppg_multi_100hz_inst_set_client_config\s*-\s*g:(?<g>\d),r:(?<r>\d),ir:(?<ir>\d)")
            if ($config100.Success) {
                if ($config100.Groups["g"].Value -eq "0" -and $config100.Groups["r"].Value -eq "0" -and $config100.Groups["ir"].Value -eq "0") {
                    if ($null -eq $hubLastStop100 -or $timestamp -gt $hubLastStop100) { $hubLastStop100 = $timestamp }
                } else {
                    if ($null -eq $hubLastStart100 -or $timestamp -gt $hubLastStart100) { $hubLastStart100 = $timestamp }
                }
            }
            if ($rest -match "sns_ppg_multi_100hz_set_client_request\s*-\s*rm:1") {
                if ($null -eq $hubLastStop100 -or $timestamp -gt $hubLastStop100) { $hubLastStop100 = $timestamp }
            }
            $led25 = [regex]::Match($rest, "ppg_multi_25hz_request_set_led\s*-\s*value:(?<value>\d+)")
            if ($led25.Success) {
                if ($null -eq $hubLastLed25 -or $timestamp -gt $hubLastLed25) { $hubLastLed25 = $timestamp }
            }
            $led100 = [regex]::Match($rest, "ppg_multi_100hz_request_set_led\s*-\s*value:(?<value>\d+)")
            if ($led100.Success) {
                if ($null -eq $hubLastLed100 -or $timestamp -gt $hubLastLed100) { $hubLastLed100 = $timestamp }
            }
        }
    }
    $hub.samples25 = $hubSamples25
    $hub.samples100 = $hubSamples100
    $hub.activeSamples25 = $hubActiveSamples25
    $hub.activeSamples100 = $hubActiveSamples100
    $hub.lastSample25 = $hubLastSample25
    $hub.lastSample100 = $hubLastSample100
    $hub.lastStop25 = $hubLastStop25
    $hub.lastStop100 = $hubLastStop100
    $hub.lastStart25 = $hubLastStart25
    $hub.lastStart100 = $hubLastStart100
    $hub.lastColor25 = $hubLastColor25
    $hub.lastColor100 = $hubLastColor100
    $hub.lastLed25 = $hubLastLed25
    $hub.lastLed100 = $hubLastLed100
    $hub.active25 = ($null -ne $hubLastSample25) -and (($null -eq $hubLastStop25) -or ($hubLastSample25 -gt $hubLastStop25))
    $hub.active100 = ($null -ne $hubLastSample100) -and (($null -eq $hubLastStop100) -or ($hubLastSample100 -gt $hubLastStop100))
}

# ---------------------------------------------------------------------------
# 7. Findings and layer conclusions
# ---------------------------------------------------------------------------
$findings = New-Object System.Collections.Generic.List[object]
$missingEvidence = New-Object System.Collections.Generic.List[string]
$nextActions = New-Object System.Collections.Generic.List[string]

foreach ($session in $sessions) {
    $shortId = if ($session.sessionId.Length -ge 8) { $session.sessionId.Substring(0, 8) } else { $session.sessionId }
    if ($session.eventOnly) {
        Add-Finding $findings "TEST_ABORTED_NO_SENSOR" "TestHarness" "INFO" "CONFIRMED" `
            "Session $shortId has only PRE-1/EVENT rows; no sensor started. This is not a product negative result." `
            @("session=$($session.sessionId)", "seal=$($session.sealReason)", "counts=$($session.counts | ConvertTo-Json -Compress)")
    }
    if ($session.hasEcg -and -not $session.hasCore -and $session.sealReason -match "ECG_IDLE_TIMEOUT") {
        Add-Finding $findings "TEST_ABORTED_PREREQUISITE" "TestHarness" "INFO" "CONFIRMED" `
            "Session $shortId never reached CORE: pre-ECG contact timed out. It cannot answer whether CORE+post ECG triggers LED." `
            @("session=$($session.sessionId)", "seal=$($session.sealReason)", "counts=$($session.counts | ConvertTo-Json -Compress)")
    }
    if (-not $session.eventOnly -and -not $session.hasCore -and ($session.coreSources -contains "PPG")) {
        Add-Finding $findings "CAPTURE_TARGET_NOT_REACHED" "L1_APP_STATE" "WARNING" "CONFIRMED" `
            "Session $shortId requested CORE PPG but has no CORE-* rows. Inspect the seal reason and prior phase events." `
            @("session=$($session.sessionId)", "seal=$($session.sealReason)", "counts=$($session.counts | ConvertTo-Json -Compress)")
    }
    if ($session.integrity -ne "" -and $session.integrity -ne "SEALED_VERIFIED") {
        Add-Finding $findings "STORAGE_SEAL_FAILED" "L8_TRANSPORT_STORAGE" "ERROR" "CONFIRMED" `
            "Session $shortId is not SEALED_VERIFIED." @("session=$($session.sessionId)", "integrity=$($session.integrity)")
    }
}
if ($sessions.Count -eq 0) { $missingEvidence.Add("activity session ZIP (plan.json + seal.json + raw blocks)") }

if ($createdSensors.Count -gt 0) {
    $missingUnset = @($createdSensors | Where-Object { -not $finalReturned.Contains($_) })
    if ($finalFailed.Count -gt 0 -or $missingUnset.Count -gt 0) {
        Add-Finding $findings "APP_TRACKER_UNSET_INCOMPLETE" "L2_APP_RESOURCES" "ERROR" "CONFIRMED" `
            "Vendor tracker creation has no final unset return for every type." `
            @("created=$(($createdSensors | ForEach-Object { $_ }) -join ',')", "finalReturned=$(($finalReturned | ForEach-Object { $_ }) -join ',')",
              "finalFailed=$(($finalFailed | ForEach-Object { $_ }) -join ',')", "missing=$($missingUnset -join ',')")
    } else {
        Add-Finding $findings "APP_TRACKER_UNSET_EVENTS_PRESENT" "L2_APP_RESOURCES" "INFO" "APP_ONLY" `
            "Final unset was requested and returned for every created vendor tracker. This is not a hardware receipt." `
            @("created=$(($createdSensors | ForEach-Object { $_ }) -join ',')", "finalReturned=$(($finalReturned | ForEach-Object { $_ }) -join ',')")
    }
} else {
    $missingEvidence.Add("CAPTURE_VENDOR_CLEANUP tracker ledger (if a capture ran)")
}
if ($disconnectFailed) {
    Add-Finding $findings "APP_VENDOR_DISCONNECT_FAILED" "L2_APP_RESOURCES" "ERROR" "CONFIRMED" `
        "disconnectService() failed or threw." @("DISCONNECT_FAILED")
} elseif ($disconnectRequested -and -not $disconnectReturned) {
    Add-Finding $findings "APP_VENDOR_DISCONNECT_NO_RETURN" "L2_APP_RESOURCES" "WARNING" "UNKNOWN" `
        "disconnectService() was requested but no return was recorded." @("DISCONNECT_REQUEST")
}
if ($ownerClosedSeen -and $ownerClosedDetail -match "failure=(?!\s*(?:$|;))") {
    Add-Finding $findings "APP_OWNER_CLOSE_FAILED" "L2_APP_RESOURCES" "ERROR" "CONFIRMED" `
        "Owner close reported a failure." @($ownerClosedDetail)
}
if ($sessionEndDetail -match "NOT_SEALED") {
    Add-Finding $findings "APP_SESSION_NOT_SEALED" "L8_TRANSPORT_STORAGE" "ERROR" "CONFIRMED" `
        "SESSION_END contains NOT_SEALED." @($sessionEndDetail)
}
if ($persistenceDetail -match "journalFailure=(?!\s*(?:[,}]|$)).+") {
    Add-Finding $findings "APP_DIAGNOSTIC_JOURNAL_FAILED" "L2_APP_RESOURCES" "WARNING" "CONFIRMED" `
        "Persistence summary reports a journal write failure." @($persistenceDetail)
}
if ($null -ne $syncSuccess -and -not $syncSuccess) {
    Add-Finding $findings "TRANSPORT_SYNC_FAILED" "L8_TRANSPORT_STORAGE" "ERROR" "CONFIRMED" `
        "Activity sync finished with success=false." @($syncText)
}
if (-not $dumpEvidence.available) {
    Add-Finding $findings "SYSTEM_DUMP_MISSING" "L3_ANDROID_FRAMEWORK" "INFO" "UNKNOWN" `
        "No dumpstate/bugreport was provided. L3-L6 cannot be evaluated." @()
    $missingEvidence.Add("system dump (dumpsys sensorservice / activity services / ps / logcat)")
}
if ($dumpEvidence.available) {
    if ($sensorActiveRawWave) {
        Add-Finding $findings "SENSORMANAGER_RAWWAVE_ACTIVE" "L3_ANDROID_FRAMEWORK" "ERROR" "CONFIRMED" `
            "dumpsys sensorservice active list still contains a RawWave SensorManager client." @($sensorActiveLines -join "`n")
    } elseif ($sensorRawWaveHistory) {
        Add-Finding $findings "SENSORMANAGER_RAWWAVE_HISTORY_ONLY" "L3_ANDROID_FRAMEWORK" "INFO" "APP_ONLY" `
            "RawWave appears only in sensor history, not the active sensor list at dump time. This does not cover vendor PPG trackers." @()
    }
    if ($sensorActivePpg) {
        Add-Finding $findings "SENSORMANAGER_PPG_ACTIVE" "L3_ANDROID_FRAMEWORK" "ERROR" "CONFIRMED" `
            "dumpsys sensorservice active list contains a PPG sensor." @($sensorActiveLines -join "`n")
    } elseif ($sensorActiveLines.Count -gt 0) {
        Add-Finding $findings "SENSORMANAGER_NO_ACTIVE_PPG" "L3_ANDROID_FRAMEWORK" "INFO" "CONFIRMED" `
            "No active PPG entry in the SensorManager active list at dump time. Vendor SDK trackers are not listed here." @()
    }
    if ($null -ne $healthServiceBlock) {
        Add-Finding $findings "VENDOR_HEALTH_SENSOR_SERVICE_RUNNING" "L5_VENDOR_SERVICE" "WARNING" "CONFIRMED" `
            "com.samsung.android.wear.shealth HealthSensorService has a service record at dump time." @($healthServiceBlock)
    } else {
        Add-Finding $findings "VENDOR_HEALTH_SENSOR_SERVICE_NOT_FOUND" "L5_VENDOR_SERVICE" "INFO" "UNKNOWN" `
            "No HealthSensorService record was found in the activity dump. Absence in this section is not proof of process exit." @()
    }
    if ($null -ne $wearTrackerBlock) {
        Add-Finding $findings "VENDOR_WEAR_TRACKER_SERVICE_RECORD_PRESENT" "L5_VENDOR_SERVICE" "WARNING" "CONFIRMED" `
            "WearTrackerService has a service record at dump time." @($wearTrackerBlock)
    } else {
        Add-Finding $findings "VENDOR_WEAR_TRACKER_SERVICE_NOT_FOUND" "L5_VENDOR_SERVICE" "INFO" "UNKNOWN" `
            "No WearTrackerService record was found in the activity dump. Absence is not proof of process exit." @()
    }
    if ($psHealth.Count -gt 0 -or $psWearHealth.Count -gt 0) {
        Add-Finding $findings "VENDOR_HEALTH_PROCESS_PRESENT" "L5_VENDOR_SERVICE" "INFO" "CONFIRMED" `
            "A Samsung health process was present at dump time. Process presence does not prove it owns the PPG stream." @($psHealth + $psWearHealth)
    }
    if ($psRawWave.Count -gt 0) {
        Add-Finding $findings "RAWWAVE_PROCESS_PRESENT" "L3_ANDROID_FRAMEWORK" "INFO" "CONFIRMED" `
            "The RawWave process was present at dump time; this does not mean a sensor tracker was active." @($psRawWave)
    }
}
if ($hub.available) {
    if ($hub.active25) {
        Add-Finding $findings "PPG_25HZ_STREAM_ACTIVE_AT_LOG_END" "L6_SENSOR_HUB" "ERROR" "CONFIRMED" `
            "nanohub shows active ppg_multi_25hz samples after the last stop/config-off marker." `
            @("activeSamples=$($hub.activeSamples25)", "lastSampleSec=$($hub.lastSample25)",
              "lastStopSec=$($hub.lastStop25)", "lastColor=$($hub.lastColor25)")
    } elseif ($hub.activeSamples25 -gt 0) {
        Add-Finding $findings "PPG_25HZ_STREAM_STOPPED" "L6_SENSOR_HUB" "INFO" "CONFIRMED" `
            "nanohub shows ppg_multi_25hz samples and a later stop/config-off marker." `
            @("activeSamples=$($hub.activeSamples25)", "lastSampleSec=$($hub.lastSample25)", "lastStopSec=$($hub.lastStop25)")
    }
    if ($hub.active100) {
        Add-Finding $findings "PPG_100HZ_STREAM_ACTIVE_AT_LOG_END" "L6_SENSOR_HUB" "ERROR" "CONFIRMED" `
            "nanohub shows active ppg_multi_100hz samples after the last stop/config-off marker." `
            @("activeSamples=$($hub.activeSamples100)", "lastSampleSec=$($hub.lastSample100)",
              "lastStopSec=$($hub.lastStop100)", "lastColor=$($hub.lastColor100)")
    } elseif ($hub.activeSamples100 -gt 0) {
        Add-Finding $findings "PPG_100HZ_STREAM_STOPPED" "L6_SENSOR_HUB" "INFO" "CONFIRMED" `
            "nanohub shows ppg_multi_100hz samples and a later stop/config-off marker." `
            @("activeSamples=$($hub.activeSamples100)", "lastSampleSec=$($hub.lastSample100)", "lastStopSec=$($hub.lastStop100)")
    }
    if ($null -ne $hub.lastLed25 -and $null -ne $hub.lastStop25 -and $hub.lastLed25 -gt $hub.lastStop25) {
        Add-Finding $findings "PPG_LED25_REQUEST_AFTER_STOP" "L6_SENSOR_HUB" "ERROR" "CONFIRMED" `
            "The 25 Hz LED request marker appears after the last stop marker." @()
    }
} else {
    Add-Finding $findings "NANOHUB_LOG_MISSING" "L6_SENSOR_HUB" "INFO" "UNKNOWN" `
        "No nanohub-log files were provided. L6 cannot be evaluated." @()
    $missingEvidence.Add("watch nanohub logs (data_vendor_log.zip or extracted nanohub-log-*.txt)")
}

$captureRecords = @()
foreach ($event in $observations) {
    $details = Get-Field $event "details"
    if ($null -eq $details) { continue }
    $record = [ordered]@{
        utc = As-String (Get-Field $event "utc")
        captureId = As-String (Get-Field $details "diagnosticCaptureId")
        symptoms = @((Get-Field $details "symptoms") | ForEach-Object { As-String $_ })
        led = As-String (Get-Field $details "led")
        watchExit = As-String (Get-Field $details "watchExit")
        dataArrival = As-String (Get-Field $details "dataArrival")
        rebootState = As-String (Get-Field $details "rebootState")
    }
    $captureRecords += [pscustomobject]$record
}
foreach ($capture in $captureRecords) {
    if ($capture.led -eq "ON") {
        Add-Finding $findings "USER_REPORTED_LED_ON" "L7_HARDWARE" "WARNING" "USER_REPORT" `
            "User recorded persistent LED at $($capture.utc)." @("capture=$($capture.captureId)", "symptoms=$($capture.symptoms -join ',')")
    }
    if ($capture.watchExit -eq "NOT_EXITED") {
        Add-Finding $findings "USER_REPORTED_WATCH_NOT_EXITED" "L1_APP_STATE" "WARNING" "USER_REPORT" `
            "User recorded that the watch did not exit/return to the watch face." @("capture=$($capture.captureId)")
    }
}
if ($captureRecords.Count -eq 0) {
    $missingEvidence.Add("structured user observation (use '记录问题现场' in the app)")
}

# ---------------------------------------------------------------------------
# 8. Primary classification
# ---------------------------------------------------------------------------
$primary = "INSUFFICIENT_EVIDENCE"
$confidence = "UNKNOWN"
$hasHubActive = @($findings | Where-Object { $_.code -eq "PPG_25HZ_STREAM_ACTIVE_AT_LOG_END" }).Count -gt 0
$hasAppFailure = @($findings | Where-Object { $_.code -match "^APP_.*FAILED|^APP_TRACKER_UNSET_INCOMPLETE|^APP_SESSION_NOT_SEALED" }).Count -gt 0
$hasTestAbort = @($findings | Where-Object { $_.code -match "^TEST_ABORTED" }).Count -gt 0
if ($hasHubActive) {
    $primary = "VENDOR_SENSOR_HUB_OR_FIRMWARE"
    $confidence = "CONFIRMED_TO_HUB"
    $nextActions.Add("Do not run more app-only retests. Preserve the nanohub log and the user observation.")
    $nextActions.Add("Capture one watch dump after reboot to check whether the stream clears; then escalate to Samsung with the hub log.")
} elseif ($hasAppFailure) {
    $primary = "APP_RESOURCE_OR_STORAGE"
    $confidence = "CONFIRMED_APP_ONLY"
    $nextActions.Add("Inspect the named app events and fix the resource/storage path before any new device test.")
} elseif ($hasTestAbort) {
    $primary = "TEST_HARNESS_OR_PREREQUISITE"
    $confidence = "CONFIRMED"
    $nextActions.Add("Fix the test precondition or add a skip path; do not treat this run as a product negative.")
} elseif (-not $dumpEvidence.available) {
    $primary = "INSUFFICIENT_SYSTEM_EVIDENCE"
    $confidence = "UNKNOWN"
    $nextActions.Add("Capture a system dump using the Galaxy Wearable hidden menu or adb bugreport.")
} elseif ($sessions.Count -gt 0) {
    $primary = "NO_ANOMALY_IN_PROVIDED_EVIDENCE"
    $confidence = "LIMITED"
    $nextActions.Add("The provided evidence does not show the symptom. Capture the report and system dump at the time the problem is present.")
}
if ($hub.available -and $hub.active25) {
    $nextActions.Add("Compare the hub timestamp range with the report UTC window; vendor client/UID mapping is still missing.")
}
if ($captureRecords.Count -eq 0) {
    $nextActions.Add("Next time the symptom appears, tap '记录问题现场' before rebooting or force-stopping.")
}

# ---------------------------------------------------------------------------
# 9. Layer table
# ---------------------------------------------------------------------------
$layers = @{}
Set-Layer $layers "L0_USER_UI" $(if ($captureRecords.Count -gt 0) { "USER_REPORTED" } else { "NOT_COLLECTED" }) `
    $(if ($captureRecords.Count -gt 0) { "Structured user observation is present." } else { "No structured problem-site record." })
Set-Layer $layers "L1_APP_STATE" $(if ($appEvents.Count -gt 0 -or $sessions.Count -gt 0) { "APP_SELF_REPORT" } else { "NOT_COLLECTED" }) `
    "App journal and activity seal only; absence of an event is not proof that nothing happened."
Set-Layer $layers "L2_APP_RESOURCES" $(if ($hasAppFailure) { "APP_SELF_REPORT_FAILURE" } elseif ($createdSensors.Count -gt 0) { "APP_SELF_REPORT_CLEAN_EVENTS" } else { "NOT_COLLECTED" }) `
    "unset/disconnect are requests and returns, not hardware receipts." @{ hardwareStoppedVerified = $false }
Set-Layer $layers "L3_ANDROID_FRAMEWORK" $(if (-not $dumpEvidence.available) { "NOT_COLLECTED" } elseif ($sensorActiveRawWave -or $sensorActivePpg) { "ACTIVE_SENSOR_FOUND" } else { "NO_ACTIVE_RAWWAVE_PPG" }) `
    $(if ($dumpEvidence.available) { "Parsed dumpsys sensorservice active list." } else { "System dump missing." })
Set-Layer $layers "L4_VENDOR_SDK" $(if ($null -ne $sdkCapability -or $createdSensors.Count -gt 0) { "APP_SIDE_ONLY" } else { "NOT_COLLECTED" }) `
    $(if ($null -ne $sdkCapability) { "Health Sensor Service capability recorded; see capability fields." } else { "No capability event; SDK logs may still be present." })
Set-Layer $layers "L5_VENDOR_SERVICE" $(if (-not $dumpEvidence.available) { "NOT_COLLECTED" } elseif ($null -ne $healthServiceBlock) { "HEALTH_SENSOR_SERVICE_RECORD_PRESENT" } else { "NO_SERVICE_RECORD_OBSERVED" }) `
    "Service/process presence alone does not prove which client owns the PPG stream."
Set-Layer $layers "L6_SENSOR_HUB" $(if (-not $hub.available) { "NOT_COLLECTED" } elseif ($hub.active25 -or $hub.active100) { "STREAM_ACTIVE_AT_LOG_END" } else { "STREAM_STOPPED_OR_NOT_OBSERVED" }) `
    "nanohub/PPG stream evidence; timestamps are sensor-hub clock, not UTC."
Set-Layer $layers "L7_HARDWARE" $(if ($captureRecords.Count -gt 0) { "USER_REPORTED" } else { "UNKNOWN" }) `
    "Only user observation/reboot behavior can support hardware; App logs cannot."
Set-Layer $layers "L8_TRANSPORT_STORAGE" $(if ($sessions.Count -gt 0 -or $null -ne $syncSuccess) { "APP_SELF_REPORT" } else { "NOT_COLLECTED" }) `
    "Seal counts and sync events; activity raw data is not included in a diagnostic report."

# ---------------------------------------------------------------------------
# 10. Output
# ---------------------------------------------------------------------------
$inputBase = if (Test-Path -LiteralPath $inputResolved -PathType Container) { $inputResolved } else { Split-Path -Parent $inputResolved }
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $inputBase ("rawwave-analysis-" + [DateTime]::UtcNow.ToString("yyyyMMdd-HHmmss"))
}
New-Item -ItemType Directory -Path $OutputPath -Force | Out-Null

$result = [ordered]@{
    schemaVersion = 1
    kind = "RAWWAVE_DIAGNOSTIC_ANALYSIS"
    generatedUtc = [DateTime]::UtcNow.ToString("o")
    input = $inputResolved
    output = (Resolve-Path -LiteralPath $OutputPath).Path
    productPackage = $ProductPackage
    requestedCaptureId = $CaptureId
    window = [ordered]@{ startUtc = $WindowStartUtc; endUtc = $WindowEndUtc; note = "Filtering is advisory; evidence timestamps may use different clocks." }
    correlation = [ordered]@{
        requestedCaptureId = $CaptureId
        mode = $correlationMode
        referenceUtc = $referenceUtc
        selectedCleanupOwner = if ($null -eq $selectedLedger) { "NONE" } else { $selectedLedger.ownerId }
    }
    classification = $primary
    confidence = $confidence
    layers = $layers
    findings = @($findings | ForEach-Object { $_ })
    sessions = @($sessions | ForEach-Object { $_ })
    observations = @($captureRecords | ForEach-Object { $_ })
    app = [ordered]@{
        eventFiles = @($appEventFiles | ForEach-Object { $_ })
        eventCount = $appEvents.Count
        corruptEventLines = $appEventCorrupt
        observationCount = $observations.Count
        corruptObservationLines = $observationCorrupt
        lastEventUtc = $lastEventUtc
        processRunIds = @($processRunIds | ForEach-Object { $_ })
        sessionIds = @($sessionIds | ForEach-Object { $_ })
        createdSensors = @($createdSensors | ForEach-Object { $_ })
        finalUnsetReturned = @($finalReturned | ForEach-Object { $_ })
        finalUnsetFailed = @($finalFailed | ForEach-Object { $_ })
        disconnectRequested = $disconnectRequested
        disconnectReturned = $disconnectReturned
        disconnectFailed = $disconnectFailed
        recheckDetail = $recheckDetail
        ownerClosedDetail = $ownerClosedDetail
        sessionEndDetail = $sessionEndDetail
        persistenceDetail = $persistenceDetail
        syncSuccess = $syncSuccess
        syncText = $syncText
        exitRequested = $exitRequested
        capability = $sdkCapability
        selectedCleanupOwner = if ($null -eq $selectedLedger) { "NONE" } else { $selectedLedger.ownerId }
        cleanupOwners = @($cleanupOwnerSummaries | ForEach-Object { $_ })
        buildIdentities = @($buildIdentities | ForEach-Object { $_ })
    }
    system = [ordered]@{
        dumpAvailable = $dumpEvidence.available
        dumpFile = $dumpEvidence.file
        sensorActivePpg = $sensorActivePpg
        sensorActiveRawWave = $sensorActiveRawWave
        sensorRawWaveHistory = $sensorRawWaveHistory
        sensorActiveLines = @($sensorActiveLines | ForEach-Object { $_ })
        healthServiceRecord = $healthServiceBlock
        wearTrackerServiceRecord = $wearTrackerBlock
        rawWaveServiceRecords = @($rawWaveServiceBlocks | ForEach-Object { $_ })
        psHealth = @($psHealth | ForEach-Object { $_ })
        psWearHealth = @($psWearHealth | ForEach-Object { $_ })
        psRawWave = @($psRawWave | ForEach-Object { $_ })
        logcatRelevant = @($dumpEvidence.logcatLines | Select-Object -First 80 | ForEach-Object { $_ })
    }
    hub = $hub
    missingEvidence = @($missingEvidence | ForEach-Object { $_ })
    nextActions = @($nextActions | ForEach-Object { $_ })
    nonClaims = @(
        "App cleanup events do not prove the vendor sensor hub or LED stopped.",
        "The absence of an anomaly in the provided files is not proof that the product is healthy.",
        "A test prerequisite failure is not a product negative result.",
        "Sensor-hub timestamps are not automatically aligned to UTC; compare with the report window.",
        "This analyser does not read health sample values and does not make a medical diagnosis."
    )
}

$jsonPath = Join-Path $OutputPath "analysis.json"
[System.IO.File]::WriteAllText($jsonPath, ($result | ConvertTo-Json -Depth 16), (New-Object System.Text.UTF8Encoding($false)))

$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine("# RawWave 诊断分析")
[void]$md.AppendLine("")
[void]$md.AppendLine("生成时间（UTC）：$($result.generatedUtc)")
[void]$md.AppendLine("输入：``$inputResolved``")
[void]$md.AppendLine("主分类：**$primary** / 置信度：**$confidence**")
[void]$md.AppendLine("")
[void]$md.AppendLine("## 分层结论")
[void]$md.AppendLine("")
[void]$md.AppendLine("| 层 | 状态 | 证据边界 |")
[void]$md.AppendLine("| --- | --- | --- |")
foreach ($key in @("L0_USER_UI","L1_APP_STATE","L2_APP_RESOURCES","L3_ANDROID_FRAMEWORK","L4_VENDOR_SDK","L5_VENDOR_SERVICE","L6_SENSOR_HUB","L7_HARDWARE","L8_TRANSPORT_STORAGE")) {
    [void]$md.AppendLine("| $key | $($layers[$key].status) | $($layers[$key].evidence) |")
}
[void]$md.AppendLine("")
[void]$md.AppendLine("## 发现")
[void]$md.AppendLine("")
if ($findings.Count -eq 0) { [void]$md.AppendLine("未发现可表达的规则命中；这不等于没有问题。") }
else {
    foreach ($finding in $findings) {
        [void]$md.AppendLine("### $($finding.code) · $($finding.layer) · $($finding.severity) / $($finding.confidence)")
        [void]$md.AppendLine("")
        [void]$md.AppendLine($finding.summary)
        [void]$md.AppendLine("")
        foreach ($evidence in $finding.evidence) { [void]$md.AppendLine("- $evidence") }
        [void]$md.AppendLine("")
    }
}
[void]$md.AppendLine("## 活动会话")
[void]$md.AppendLine("")
if ($sessions.Count -eq 0) { [void]$md.AppendLine("没有活动 ZIP。") }
else {
    [void]$md.AppendLine("| session | 模板 | 封存原因 | 行数 | 完整性 |")
    [void]$md.AppendLine("| --- | --- | --- | --- | --- |")
    foreach ($session in $sessions) {
        $short = if ($session.sessionId.Length -ge 8) { $session.sessionId.Substring(0,8) } else { $session.sessionId }
        [void]$md.AppendLine("| $short | $($session.template) | $($session.sealReason) | $($session.totalRows) | $($session.integrity) |")
    }
}
[void]$md.AppendLine("")
[void]$md.AppendLine("## Sensor hub")
[void]$md.AppendLine("")
[void]$md.AppendLine("- 日志文件数：$($hub.fileCount)")
[void]$md.AppendLine("- 25 Hz 活跃样本：$($hub.activeSamples25)，最后样本：$($hub.lastSample25)，最后停止标记：$($hub.lastStop25)，末尾活跃：$($hub.active25)")
[void]$md.AppendLine("- 100 Hz 活跃样本：$($hub.activeSamples100)，最后样本：$($hub.lastSample100)，最后停止标记：$($hub.lastStop100)，末尾活跃：$($hub.active100)")
[void]$md.AppendLine("- 说明：hub 时间戳是设备启动后秒数，不能自动当成 UTC。")
[void]$md.AppendLine("")
[void]$md.AppendLine("## 用户观察")
[void]$md.AppendLine("")
if ($captureRecords.Count -eq 0) { [void]$md.AppendLine("没有「记录问题现场」的结构化观察。") }
else {
    foreach ($capture in $captureRecords) {
        [void]$md.AppendLine("- $($capture.utc) $($capture.captureId)：症状=$($capture.symptoms -join ',')；LED=$($capture.led)；手表退出=$($capture.watchExit)；重启=$($capture.rebootState)")
    }
}
[void]$md.AppendLine("")
[void]$md.AppendLine("## 缺少的证据")
[void]$md.AppendLine("")
if ($missingEvidence.Count -eq 0) { [void]$md.AppendLine("没有登记缺失项；仍不能据此宣称系统健康。") }
else { foreach ($item in $missingEvidence) { [void]$md.AppendLine("- $item") } }
[void]$md.AppendLine("")
[void]$md.AppendLine("## 下一步")
[void]$md.AppendLine("")
if ($nextActions.Count -eq 0) { [void]$md.AppendLine("没有额外动作；不要为了复现而反复测试。") }
else { foreach ($action in $nextActions) { [void]$md.AppendLine("- $action") } }
[void]$md.AppendLine("")
[void]$md.AppendLine("## 不能证明什么")
[void]$md.AppendLine("")
foreach ($claim in $result.nonClaims) { [void]$md.AppendLine("- $claim") }

$mdPath = Join-Path $OutputPath "analysis.md"
[System.IO.File]::WriteAllText($mdPath, $md.ToString(), (New-Object System.Text.UTF8Encoding($false)))

$summary = @(
    "classification=$primary",
    "confidence=$confidence",
    "findings=$($findings.Count)",
    "sessions=$($sessions.Count)",
    "observations=$($captureRecords.Count)",
    "hub.active25=$($hub.active25)",
    "hub.active100=$($hub.active100)",
    "systemDump=$($dumpEvidence.available)"
) -join "`n"
[System.IO.File]::WriteAllText((Join-Path $OutputPath "analysis-summary.txt"), $summary + "`n", (New-Object System.Text.UTF8Encoding($false)))

Write-Step "classification=$primary confidence=$confidence"
Write-Step "output=$OutputPath"
try { Remove-Item -LiteralPath $script:work -Recurse -Force -ErrorAction SilentlyContinue } catch { }
Write-Output (Join-Path $OutputPath "analysis.json")
