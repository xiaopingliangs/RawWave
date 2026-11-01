$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
# Architectural guard only; this is not a replacement for behavioral or device tests.
$core = Get-ChildItem (Join-Path $root 'shared/src/main/java') -Recurse -Filter '*.java'
foreach ($file in $core) {
    if ((Get-Content -LiteralPath $file.FullName -Raw) -match '(?m)^import\s+(android\.|com\.google\.android\.|com\.samsung\.)') {
        throw "Platform dependency leaked into core: $($file.Name)"
    }
}
$service = Get-Content (Join-Path $root 'wear/src/main/java/com/rawwave/shell/ActivityRecordingService.kt') -Raw
if ($service -match 'SamsungSensorAdapter|com\.samsung\.' -or $service -notmatch 'adapter: SensorPort\?') {
    throw 'Capture owner bypasses SensorPort'
}
$wear = Get-ChildItem (Join-Path $root 'wear/src/main/java') -Recurse -Filter '*.kt'
foreach ($file in $wear) {
    if ($file.Name -notin @('SamsungSensorAdapter.kt','CaptureAssembly.kt') -and
        (Get-Content -LiteralPath $file.FullName -Raw) -match 'SamsungSensorAdapter\s*\(') {
        throw "Vendor construction outside assembly: $($file.Name)"
    }
}
foreach ($name in @('TransferWindow.kt','ActivityTransferWindow.kt')) {
    $text=Get-Content (Join-Path $root "app-common/java/com/rawwave/shell/$name") -Raw
    if ($text -match 'com\.google\.android|Wearable\.|ChannelClient|MessageClient|Tasks\.await' -or
        $text -notmatch 'transportPort: TransportPort\?') { throw "Transport bypass: $name" }
}
Write-Output 'MODULE_BOUNDARIES_PASS: core platform imports; capture/transport interfaces; vendor assembly'
$phoneEntry=Get-Content (Join-Path $root 'app-common/java/com/rawwave/shell/PhoneWakeService.kt') -Raw
if ($phoneEntry -match 'FileOutputStream|Files\.move|Os\.fsync' -or $phoneEntry -notmatch 'summaryReceiver.receive') {
    throw 'Phone summary entry bypasses storage handoff'
}
$summary=Get-Content (Join-Path $root 'app-common/java/com/rawwave/shell/DiagnosticSummaryTransport.kt') -Raw
if ($summary -match 'com\.google\.android|Wearable\.|MessageClient|Tasks\.await' -or $summary -notmatch 'transportFactory: \(\) -> TransportPort') {
    throw 'Diagnostic summary bypasses transport contract'
}
