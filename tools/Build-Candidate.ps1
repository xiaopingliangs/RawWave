param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$RunId,
    [string]$Jdk = 'D:\Program Files\Android\Android Studio\jbr',
    [string]$Sdk = 'D:\Projects\androidsdk',
    [string]$Gradle = 'C:\Users\xiaop\.gradle\wrapper\dists\gradle-9.3.1-bin\23ovyewtku6u96viwx3xl3oks\gradle-9.3.1\bin\gradle.bat'
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$candidateRoot = Join-Path $projectRoot "dist/$RunId"
if (Test-Path -LiteralPath $candidateRoot) { throw 'RunId already exists; never overwrite a candidate.' }
New-Item -Path $candidateRoot -ItemType Directory | Out-Null
$started = [DateTimeOffset]::Now
$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk

function Get-SourceInventory {
    @(Get-ChildItem -LiteralPath $projectRoot -File -Recurse | Where-Object {
        $_.FullName -notmatch '[\\/](build|dist|\.gradle|\.git|evidence)[\\/]' -and
        $_.Extension -in '.kt','.java','.xml','.kts','.properties','.ps1','.aar'
    } | Sort-Object FullName | ForEach-Object {
        $relativePath = $_.FullName.Substring($projectRoot.Length).TrimStart([char]'\',[char]'/').Replace('\','/')
        [ordered]@{ path = $relativePath; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    })
}

function Get-DefinedDexTypes([string]$Apk) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Apk)
    $definedTypes = [Collections.Generic.HashSet[string]]::new()
    try {
        foreach ($entry in $archive.Entries | Where-Object FullName -match '^classes\d*\.dex$') {
            $stream = $entry.Open(); $memory = [IO.MemoryStream]::new()
            try { $stream.CopyTo($memory); $dex = $memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
            if ($dex.Length -lt 112 -or [Text.Encoding]::ASCII.GetString($dex,0,4) -ne "dex`n") { throw 'Invalid DEX header' }
            $stringOffset = [BitConverter]::ToUInt32($dex,60)
            $typeOffset = [BitConverter]::ToUInt32($dex,68)
            $classCount = [BitConverter]::ToUInt32($dex,96)
            $classOffset = [BitConverter]::ToUInt32($dex,100)
            for ($index=0; $index -lt $classCount; $index++) {
                $typeIndex = [BitConverter]::ToUInt32($dex, ($classOffset + 32*$index))
                $stringIndex = [BitConverter]::ToUInt32($dex, ($typeOffset + 4*$typeIndex))
                $cursor = [BitConverter]::ToUInt32($dex, ($stringOffset + 4*$stringIndex))
                $ulebBytes = 0
                do { $byte = $dex[$cursor++]; $ulebBytes++; if ($ulebBytes -gt 5) { throw 'Invalid DEX string length' } } while (($byte -band 128) -ne 0)
                $begin = $cursor
                while ($cursor -lt $dex.Length -and $dex[$cursor] -ne 0) { $cursor++ }
                if ($cursor -ge $dex.Length) { throw 'Unterminated DEX string' }
                [void]$definedTypes.Add([Text.Encoding]::UTF8.GetString($dex,$begin,($cursor-$begin)))
            }
        }
    } finally { $archive.Dispose() }
    return ,$definedTypes
}

& (Join-Path $PSScriptRoot 'Test-ModuleBoundaries.ps1')
$sourceBefore = Get-SourceInventory
$tasks = @(':shared:test', ':mobile:testDebugUnitTest', ':wear:testDebugUnitTest', ':wear:assembleRelease', ':mobile:assembleRelease', ':wear:lintRelease', ':mobile:lintRelease')
Push-Location $projectRoot
try {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue' # Windows PowerShell maps native stderr to a non-terminating ErrorRecord.
    & $Gradle @tasks --no-daemon --rerun-tasks --no-build-cache "-PbuildRun=$RunId" 2>&1 | Tee-Object -FilePath (Join-Path $candidateRoot 'build.log')
    $gradleExit = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($gradleExit -ne 0) { throw "Gradle failed: $gradleExit" }
    $sourceAfter = Get-SourceInventory
    if (($sourceBefore | ConvertTo-Json -Depth 4 -Compress) -ne ($sourceAfter | ConvertTo-Json -Depth 4 -Compress)) { throw 'Source changed during build' }
    $captureHostFile = Join-Path $projectRoot 'wear/build/test-results/testDebugUnitTest/TEST-com.rawwave.shell.CaptureServiceTest.xml'
    if ((Get-Item -LiteralPath $captureHostFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale capture host tests' }
    [xml]$captureHostTests = Get-Content -LiteralPath $captureHostFile -Raw
    $corpusConfigured = -not [string]::IsNullOrWhiteSpace($env:RAWWAVE_REPLAY_INPUT)
    $captureHostSkipped = [int]$captureHostTests.testsuite.skipped
    $unexpectedCaptureSkips = @($captureHostTests.testsuite.testcase | Where-Object {
        $_.skipped -and $_.name -ne 'privateThreeActivityCorpusPreservesEverySavedCoreField' })
    if ($unexpectedCaptureSkips.Count -gt 0) { throw "Unexpected capture host skip: $($unexpectedCaptureSkips[0].name)" }
    if ($corpusConfigured -and $captureHostSkipped -ne 0) { throw 'Private corpus configured but its replay was skipped' }
    if (-not $corpusConfigured -and $captureHostSkipped -gt 1) { throw 'Unexpected capture host skip count' }
    if ([int]$captureHostTests.testsuite.tests -lt 16 -or [int]$captureHostTests.testsuite.failures -ne 0 -or [int]$captureHostTests.testsuite.errors -ne 0) { throw 'Capture host tests failed or missing' }
    $privateCorpusReplay = if ($corpusConfigured) { 'PASS' } else { 'NOT_RUN_PRIVATE_CORPUS_NOT_CONFIGURED' }
    Copy-Item -LiteralPath $captureHostFile -Destination (Join-Path $candidateRoot 'capture-host-tests.xml')
    $coordinatorFile = Join-Path $projectRoot 'mobile/build/test-results/testDebugUnitTest/TEST-com.rawwave.shell.ActivityCoordinatorTest.xml'
    if ((Get-Item -LiteralPath $coordinatorFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale coordinator tests' }
    [xml]$coordinatorTests = Get-Content -LiteralPath $coordinatorFile -Raw
    if ([int]$coordinatorTests.testsuite.tests -lt 10 -or [int]$coordinatorTests.testsuite.failures -ne 0 -or [int]$coordinatorTests.testsuite.errors -ne 0 -or [int]$coordinatorTests.testsuite.skipped -ne 0) { throw 'Coordinator tests failed or missing' }
    Copy-Item -LiteralPath $coordinatorFile -Destination (Join-Path $candidateRoot 'coordinator-tests.xml')
    $testFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.DiagnosticsTest.xml'
    if ((Get-Item -LiteralPath $testFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale test report' }
    [xml]$testReport = Get-Content -LiteralPath $testFile -Raw
    if ([int]$testReport.testsuite.failures -ne 0 -or [int]$testReport.testsuite.errors -ne 0 -or [int]$testReport.testsuite.tests -lt 9) { throw 'Tests failed or missing' }
    Copy-Item -LiteralPath $testFile -Destination (Join-Path $candidateRoot 'unit-tests.xml')
    $feedbackTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.DiagnosticFeedbackTest.xml'
    if ((Get-Item -LiteralPath $feedbackTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale feedback tests' }
    [xml]$feedbackTests = Get-Content -LiteralPath $feedbackTestFile -Raw
    if ([int]$feedbackTests.testsuite.failures -ne 0 -or [int]$feedbackTests.testsuite.errors -ne 0 -or [int]$feedbackTests.testsuite.skipped -ne 0 -or [int]$feedbackTests.testsuite.tests -lt 13) { throw 'Feedback tests failed or missing' }
    Copy-Item -LiteralPath $feedbackTestFile -Destination (Join-Path $candidateRoot 'feedback-tests.xml')
    $transferTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.TransferFilesTest.xml'
    if ((Get-Item -LiteralPath $transferTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale transfer test report' }
    [xml]$transferTests = Get-Content -LiteralPath $transferTestFile -Raw
    if ([int]$transferTests.testsuite.failures -ne 0 -or [int]$transferTests.testsuite.errors -ne 0 -or [int]$transferTests.testsuite.tests -lt 13) { throw 'Transfer tests failed or missing' }
    Copy-Item -LiteralPath $transferTestFile -Destination (Join-Path $candidateRoot 'transfer-tests.xml')
    $reportTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.ReportModulesTest.xml'
    if ((Get-Item -LiteralPath $reportTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale report module test report' }
    [xml]$reportTests = Get-Content -LiteralPath $reportTestFile -Raw
    if ([int]$reportTests.testsuite.failures -ne 0 -or [int]$reportTests.testsuite.errors -ne 0 -or [int]$reportTests.testsuite.skipped -ne 0 -or [int]$reportTests.testsuite.tests -lt 14) { throw 'Report module tests failed or missing' }
    Copy-Item -LiteralPath $reportTestFile -Destination (Join-Path $candidateRoot 'report-module-tests.xml')
    $runtimeTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.RunDeadlinesTest.xml'
    if ((Get-Item -LiteralPath $runtimeTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale runtime test report' }
    [xml]$runtimeTests = Get-Content -LiteralPath $runtimeTestFile -Raw
    if ([int]$runtimeTests.testsuite.failures -ne 0 -or [int]$runtimeTests.testsuite.errors -ne 0 -or [int]$runtimeTests.testsuite.skipped -ne 0 -or [int]$runtimeTests.testsuite.tests -lt 18) { throw 'Runtime tests failed or missing' }
    Copy-Item -LiteralPath $runtimeTestFile -Destination (Join-Path $candidateRoot 'runtime-tests.xml')
    $rateTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.TransferRateMonitorTest.xml'
    if ((Get-Item -LiteralPath $rateTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale transfer rate test report' }
    [xml]$rateTests = Get-Content -LiteralPath $rateTestFile -Raw
    if ([int]$rateTests.testsuite.failures -ne 0 -or [int]$rateTests.testsuite.errors -ne 0 -or [int]$rateTests.testsuite.skipped -ne 0 -or [int]$rateTests.testsuite.tests -lt 6) { throw 'Transfer rate stability tests failed or missing' }
    Copy-Item -LiteralPath $rateTestFile -Destination (Join-Path $candidateRoot 'transfer-rate-tests.xml')
    $wakeTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.WakeHistoryTest.xml'
    if ((Get-Item -LiteralPath $wakeTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale wake history report' }
    [xml]$wakeTests = Get-Content -LiteralPath $wakeTestFile -Raw
    if ([int]$wakeTests.testsuite.failures -ne 0 -or [int]$wakeTests.testsuite.errors -ne 0 -or [int]$wakeTests.testsuite.skipped -ne 0 -or [int]$wakeTests.testsuite.tests -lt 4) { throw 'Wake history tests failed or missing' }
    Copy-Item -LiteralPath $wakeTestFile -Destination (Join-Path $candidateRoot 'wake-tests.xml')
    $captureTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.ActivityStoreTest.xml'
    if ((Get-Item -LiteralPath $captureTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale capture report' }
    [xml]$captureTests = Get-Content -LiteralPath $captureTestFile -Raw
    if ([int]$captureTests.testsuite.failures -ne 0 -or [int]$captureTests.testsuite.errors -ne 0 -or [int]$captureTests.testsuite.skipped -ne 0 -or [int]$captureTests.testsuite.tests -lt 20) { throw 'Capture and sensor contract tests failed or missing' }
    Copy-Item -LiteralPath $captureTestFile -Destination (Join-Path $candidateRoot 'capture-tests.xml')
    $qualityTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.EcgQualityTest.xml'
    if ((Get-Item -LiteralPath $qualityTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale ECG quality test report' }
    [xml]$qualityTests = Get-Content -LiteralPath $qualityTestFile -Raw
    if ([int]$qualityTests.testsuite.failures -ne 0 -or [int]$qualityTests.testsuite.errors -ne 0 -or [int]$qualityTests.testsuite.skipped -ne 0 -or [int]$qualityTests.testsuite.tests -lt 10) { throw 'ECG quality tests failed or missing' }
    Copy-Item -LiteralPath $qualityTestFile -Destination (Join-Path $candidateRoot 'ecg-quality-tests.xml')
    $sensorCatalogTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.SensorCatalogTest.xml'
    if ((Get-Item -LiteralPath $sensorCatalogTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale sensor catalog test report' }
    [xml]$sensorCatalogTests = Get-Content -LiteralPath $sensorCatalogTestFile -Raw
    if ([int]$sensorCatalogTests.testsuite.failures -ne 0 -or [int]$sensorCatalogTests.testsuite.errors -ne 0 -or [int]$sensorCatalogTests.testsuite.skipped -ne 0 -or [int]$sensorCatalogTests.testsuite.tests -lt 5) { throw 'Sensor catalog tests failed or missing' }
    Copy-Item -LiteralPath $sensorCatalogTestFile -Destination (Join-Path $candidateRoot 'sensor-catalog-tests.xml')
    $syncAdmissionTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.SyncAdmissionTest.xml'
    if ((Get-Item -LiteralPath $syncAdmissionTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale sync admission test report' }
    [xml]$syncAdmissionTests = Get-Content -LiteralPath $syncAdmissionTestFile -Raw
    if ([int]$syncAdmissionTests.testsuite.failures -ne 0 -or [int]$syncAdmissionTests.testsuite.errors -ne 0 -or [int]$syncAdmissionTests.testsuite.skipped -ne 0 -or [int]$syncAdmissionTests.testsuite.tests -lt 3) { throw 'Sync admission tests failed or missing' }
    Copy-Item -LiteralPath $syncAdmissionTestFile -Destination (Join-Path $candidateRoot 'sync-admission-tests.xml')
    $autoSyncTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.AutoSyncGateTest.xml'
    if ((Get-Item -LiteralPath $autoSyncTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale auto sync test report' }
    [xml]$autoSyncTests = Get-Content -LiteralPath $autoSyncTestFile -Raw
    if ([int]$autoSyncTests.testsuite.failures -ne 0 -or [int]$autoSyncTests.testsuite.errors -ne 0 -or [int]$autoSyncTests.testsuite.skipped -ne 0 -or [int]$autoSyncTests.testsuite.tests -lt 4) { throw 'Auto sync tests failed or missing' }
    Copy-Item -LiteralPath $autoSyncTestFile -Destination (Join-Path $candidateRoot 'auto-sync-tests.xml')
    $activitySyncPolicyTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.ActivitySyncPolicyTest.xml'
    if ((Get-Item -LiteralPath $activitySyncPolicyTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale activity sync policy test report' }
    [xml]$activitySyncPolicyTests = Get-Content -LiteralPath $activitySyncPolicyTestFile -Raw
    if ([int]$activitySyncPolicyTests.testsuite.failures -ne 0 -or [int]$activitySyncPolicyTests.testsuite.errors -ne 0 -or [int]$activitySyncPolicyTests.testsuite.skipped -ne 0 -or [int]$activitySyncPolicyTests.testsuite.tests -lt 4) { throw 'Activity sync policy tests failed or missing' }
    Copy-Item -LiteralPath $activitySyncPolicyTestFile -Destination (Join-Path $candidateRoot 'activity-sync-policy-tests.xml')
    $attemptGuardTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.ActivitySyncAttemptGuardTest.xml'
    if ((Get-Item -LiteralPath $attemptGuardTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale activity sync attempt guard test report' }
    [xml]$attemptGuardTests = Get-Content -LiteralPath $attemptGuardTestFile -Raw
    if ([int]$attemptGuardTests.testsuite.failures -ne 0 -or [int]$attemptGuardTests.testsuite.errors -ne 0 -or [int]$attemptGuardTests.testsuite.skipped -ne 0 -or [int]$attemptGuardTests.testsuite.tests -lt 3) { throw 'Activity sync attempt guard tests failed or missing' }
    Copy-Item -LiteralPath $attemptGuardTestFile -Destination (Join-Path $candidateRoot 'activity-sync-attempt-guard-tests.xml')
    $helloRetryTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.HelloRetryPolicyTest.xml'
    if ((Get-Item -LiteralPath $helloRetryTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale HELLO retry test report' }
    [xml]$helloRetryTests = Get-Content -LiteralPath $helloRetryTestFile -Raw
    if ([int]$helloRetryTests.testsuite.failures -ne 0 -or [int]$helloRetryTests.testsuite.errors -ne 0 -or [int]$helloRetryTests.testsuite.skipped -ne 0 -or [int]$helloRetryTests.testsuite.tests -lt 2) { throw 'HELLO retry tests failed or missing' }
    Copy-Item -LiteralPath $helloRetryTestFile -Destination (Join-Path $candidateRoot 'hello-retry-tests.xml')
    $biaTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.BiaPressureTest.xml'
    if ((Get-Item -LiteralPath $biaTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale BIA/pressure tests' }
    [xml]$biaTests = Get-Content -LiteralPath $biaTestFile
    if ([int]$biaTests.testsuite.failures -ne 0 -or [int]$biaTests.testsuite.errors -ne 0 -or [int]$biaTests.testsuite.skipped -ne 0 -or [int]$biaTests.testsuite.tests -lt 5) { throw 'BIA/pressure tests failed or missing' }
    Copy-Item -LiteralPath $biaTestFile -Destination (Join-Path $candidateRoot 'bia-pressure-tests.xml')
    $activityArchiveTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.ActivityArchiveTest.xml'
    if ((Get-Item -LiteralPath $activityArchiveTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale activity archive test report' }
    [xml]$activityArchiveTests = Get-Content -LiteralPath $activityArchiveTestFile -Raw
    if ([int]$activityArchiveTests.testsuite.failures -ne 0 -or [int]$activityArchiveTests.testsuite.errors -ne 0 -or [int]$activityArchiveTests.testsuite.skipped -ne 0 -or [int]$activityArchiveTests.testsuite.tests -lt 16) { throw 'Activity archive and receive contract tests failed or missing' }
    Copy-Item -LiteralPath $activityArchiveTestFile -Destination (Join-Path $candidateRoot 'activity-archive-tests.xml')
    $activityExportTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.ActivityDataExporterTest.xml'
    if ((Get-Item -LiteralPath $activityExportTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale activity export test report' }
    [xml]$activityExportTests = Get-Content -LiteralPath $activityExportTestFile -Raw
    if ([int]$activityExportTests.testsuite.failures -ne 0 -or [int]$activityExportTests.testsuite.errors -ne 0 -or [int]$activityExportTests.testsuite.skipped -ne 0 -or [int]$activityExportTests.testsuite.tests -lt 12) { throw 'Activity export tests failed or missing' }
    Copy-Item -LiteralPath $activityExportTestFile -Destination (Join-Path $candidateRoot 'activity-export-tests.xml')
    $attemptTestFile = Join-Path $projectRoot 'shared/build/test-results/test/TEST-com.rawwave.core.TransferAttemptTest.xml'
    if ((Get-Item -LiteralPath $attemptTestFile).LastWriteTimeUtc -lt $started.UtcDateTime) { throw 'Stale transfer attempt tests' }
    [xml]$attemptTests = Get-Content -LiteralPath $attemptTestFile -Raw
    if ([int]$attemptTests.testsuite.failures -ne 0 -or [int]$attemptTests.testsuite.errors -ne 0 -or [int]$attemptTests.testsuite.skipped -ne 0 -or [int]$attemptTests.testsuite.tests -lt 12) { throw 'Transfer attempt contract tests failed or missing' }
    Copy-Item -LiteralPath $attemptTestFile -Destination (Join-Path $candidateRoot 'transfer-attempt-tests.xml')
    $artifacts = @()
    foreach ($module in @('wear','mobile')) {
        $apk = Join-Path $projectRoot "$module/build/outputs/apk/release/$module-release.apk"
        if ((Get-Item -LiteralPath $apk).LastWriteTimeUtc -lt $started.UtcDateTime) { throw "Stale $module APK" }
        $ErrorActionPreference = 'Continue'
        $signer = @(& (Join-Path $Sdk 'build-tools/36.0.0/apksigner.bat') verify --print-certs $apk 2>&1)
        $nativeExit = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorAction
        if ($nativeExit -ne 0) { throw "Signature check failed: $module" }
        $certificate = [regex]::Match(($signer -join "`n"),'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)').Groups[1].Value
        if (-not $certificate) { throw 'Missing certificate fingerprint' }
        $ErrorActionPreference = 'Continue'
        $badging = @(& (Join-Path $Sdk 'build-tools/36.0.0/aapt2.exe') dump badging $apk 2>&1)
        $nativeExit = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorAction
        if ($nativeExit -ne 0) { throw 'APK metadata unavailable' }
        $ErrorActionPreference = 'Continue'
        $manifest = @(& (Join-Path $Sdk 'build-tools/36.0.0/aapt2.exe') dump xmltree $apk --file AndroidManifest.xml 2>&1)
        $nativeExit = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorAction
        if ($nativeExit -ne 0) { throw 'APK manifest unavailable' }
        $manifestText = $manifest -join "`n"
        if (($badging -join "`n") -notmatch "package: name='com.rawwave.rebuild'" -or ($badging -join "`n") -match 'application-debuggable') { throw 'Unexpected package or debuggable release' }
        if ($manifestText -match 'E: (receiver|provider)\b') { throw 'Unexpected automatic component' }
        $permissions = @([regex]::Matches(($badging -join "`n"), "uses-permission: name='([^']+)'") | ForEach-Object { $_.Groups[1].Value } | Sort-Object)
        $allowedPermissions = @('android.permission.FOREGROUND_SERVICE','android.permission.FOREGROUND_SERVICE_DATA_SYNC','android.permission.WAKE_LOCK') | Sort-Object
        if ($module -eq 'wear') {
            $allowedPermissions = @($allowedPermissions) + @('android.permission.FOREGROUND_SERVICE_HEALTH','android.permission.FOREGROUND_SERVICE_LOCATION','android.permission.ACCESS_FINE_LOCATION','android.permission.ACCESS_COARSE_LOCATION','android.permission.BODY_SENSORS','android.permission.health.READ_HEART_RATE','android.permission.health.READ_SKIN_TEMPERATURE','android.permission.health.READ_OXYGEN_SATURATION','android.permission.ACTIVITY_RECOGNITION','com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA') | Sort-Object
        }
        if (($permissions -join ',') -ne ($allowedPermissions -join ',')) { throw 'Unexpected final APK permissions' }
        $services = @([regex]::Matches($manifestText, '(?ms)^(\s*)E: service\b.*?(?=^\1E:|\z)'))
        $expectedServices = 2
        if ($services.Count -ne $expectedServices) { throw 'Unexpected service count' }
        $names = @()
        foreach ($service in $services) {
            $name = [regex]::Match($service.Value, 'A: (?:http://schemas.android.com/apk/res/)?android:name[^=]*="([^"]+)"').Groups[1].Value
            $names += $name
            if ($name -eq 'com.rawwave.shell.SyncGuardService') {
                if ($service.Value -notmatch 'android:exported[^=\r\n]*=false\b' -or $service.Value -notmatch 'android:foregroundServiceType[^=\r\n]*=0x0*1\b' -or $service.Value -match 'E: intent-filter') { throw 'Invalid bounded sync service declaration' }
            } elseif ($module -eq 'wear' -and $name -eq 'com.rawwave.shell.ActivityRecordingService') {
                if ($service.Value -notmatch 'android:exported[^=\r\n]*=false\b' -or $service.Value -notmatch 'android:foregroundServiceType[^=\r\n]*=0x0*108\b' -or $service.Value -match 'E: intent-filter') { throw 'Invalid capture service declaration' }
            } elseif ($module -eq 'mobile' -and $name -eq 'com.rawwave.shell.PhoneWakeService') {
                if ($service.Value -notmatch 'android:exported[^=\r\n]*=true\b' -or $service.Value -notmatch 'com.google.android.gms.wearable.MESSAGE_RECEIVED' -or $service.Value -notmatch 'android:path[^\r\n]*="/rawwave/g2a/hello"' -or $service.Value -notmatch 'android:path[^\r\n]*="/rawwave/activity/v1/hello"' -or $service.Value -notmatch 'android:scheme[^\r\n]*="wear"' -or $service.Value -notmatch 'android:host[^\r\n]*="\*"') { throw 'Invalid phone-only wake filters' }
                if (@([regex]::Matches($service.Value,'E: action\b')).Count -ne 3 -or @([regex]::Matches($service.Value,'E: data\b')).Count -ne 3) { throw 'Unexpected wake entry expansion' }
            } else { throw "Unexpected service $name" }
        }
        if (@($names | Select-Object -Unique).Count -ne $expectedServices) { throw 'Duplicate service entry' }
        if ($manifestText -notmatch 'E: queries' -or $manifestText -notmatch 'com.google.android.wearable.app.cn') { throw 'Wear CN package visibility missing in final APK' }
        $types = Get-DefinedDexTypes $apk
        foreach ($required in @('Lcom/rawwave/core/CommitExchange;','Lcom/rawwave/core/ModuleVersions;')) {
            if (-not $types.Contains($required)) { throw "Missing commit exchange contract $required" }
        }
        foreach ($required in @('Lcom/rawwave/core/TransportPort;','Lcom/rawwave/core/TransportAwait;','Lcom/rawwave/shell/GoogleTransport;')) {
            if (-not $types.Contains($required)) { throw "Missing transport port $required" }
        }
        foreach ($required in @('Lcom/rawwave/core/ResourceHandoff;','Lcom/rawwave/shell/TransferResourceOwner;')) {
            if (-not $types.Contains($required)) { throw "Missing transport resource contract $required" }
        }
        if ($module -eq 'wear') {
            foreach ($required in @('Lcom/rawwave/core/SensorPort;','Lcom/rawwave/core/SensorRows;','Lcom/rawwave/core/LocationFix;','Lcom/rawwave/core/StepCount;','Lcom/rawwave/core/StepDetector;','Lcom/rawwave/core/OffBody;','Lcom/rawwave/core/GyroscopeUncalibrated;','Lcom/rawwave/core/MagneticFieldUncalibrated;','Lcom/rawwave/core/AccelerometerUncalibrated;','Lcom/rawwave/core/LinearAcceleration;','Lcom/rawwave/core/GameRotationVector;','Lcom/rawwave/core/RotationVector;','Lcom/rawwave/core/GeomagneticRotationVector;','Lcom/rawwave/core/Gravity;','Lcom/rawwave/core/WristTiltGesture;','Lcom/rawwave/shell/CaptureAssembly;','Lcom/rawwave/shell/LocationSensorAdapter;','Lcom/rawwave/shell/StepCounterSensorAdapter;','Lcom/rawwave/shell/StepDetectorSensorAdapter;','Lcom/rawwave/shell/OffBodySensorAdapter;','Lcom/rawwave/shell/GyroscopeUncalibratedSensorAdapter;','Lcom/rawwave/shell/MagneticFieldUncalibratedSensorAdapter;','Lcom/rawwave/shell/AccelerometerUncalibratedSensorAdapter;','Lcom/rawwave/shell/LinearAccelerationSensorAdapter;','Lcom/rawwave/shell/GameRotationVectorSensorAdapter;','Lcom/rawwave/shell/RotationVectorSensorAdapter;','Lcom/rawwave/shell/GeomagneticRotationVectorSensorAdapter;','Lcom/rawwave/shell/GravitySensorAdapter;','Lcom/rawwave/shell/WristTiltGestureSensorAdapter;')) {
                if (-not $types.Contains($required)) { throw "Missing sensor handoff $required" }
            }
        }
        foreach ($required in @('Lcom/rawwave/core/ReceiveStore;','Lcom/rawwave/core/FileReceiveStore;','Lcom/rawwave/core/SendStore;','Lcom/rawwave/core/FileSendStore;','Lcom/rawwave/core/TransferAttempt;','Lcom/rawwave/core/SyncStopScope;','Lcom/rawwave/core/ActivityExportName;','Lcom/rawwave/core/EcgWaitBudget;')) {
            if (-not $types.Contains($required)) { throw "Missing receive handoff module $required" }
        }
        if (-not $types.Contains('Lcom/rawwave/core/DiagnosticSummary;') -or -not $types.Contains('Lcom/rawwave/shell/DiagnosticSummaryTransport;')) { throw 'Summary contract or transport missing' }
        if ($module -eq 'mobile' -and $manifestText -notmatch '/rawwave/diagnostic/v1/summary') { throw 'Summary entry missing' }
        foreach ($required in @('Lcom/rawwave/core/DiagnosticFeedback;','Lcom/rawwave/shell/DiagnosticViewer;')) {
            if (-not $types.Contains($required)) { throw "Missing feedback module $required in $module" }
        }
        if ($module -eq 'wear') {
            foreach ($required in @('Lcom/rawwave/shell/RecordingActivity;','Lcom/rawwave/shell/ActivityRecordingService;','Lcom/rawwave/shell/SamsungSensorAdapter;','Lcom/rawwave/core/ActivityStore;','Lcom/rawwave/core/SensorCatalog;','Lcom/rawwave/core/EcgBatchQuality;','Lcom/rawwave/core/EcgContinuityGate;','Lcom/rawwave/core/EcgRetryPolicy;','Lcom/rawwave/core/StreamQualityTracker;','Lcom/rawwave/core/ActivityArchive;','Lcom/rawwave/core/AutoSyncGate;','Lcom/rawwave/core/ActivityRetryPolicy;','Lcom/rawwave/shell/WatchActivitySyncSession;','Lcom/samsung/android/service/health/tracking/HealthTrackingService;')) {
                if (-not $types.Contains($required)) { throw "Missing capture module $required" }
            }
            if (($badging -join "`n") -notmatch "launchable-activity: name='com.rawwave.shell.RecordingActivity'") { throw 'Capture launcher not connected' }
        }
        foreach ($required in @('Lcom/rawwave/core/RunDeadlines;','Lcom/rawwave/shell/DeadlineRuntime;','Lcom/rawwave/shell/SyncGuardService;','Lcom/rawwave/shell/PhoneReceiveSession;','Lcom/rawwave/core/WakeHistory;','Lcom/rawwave/core/ActivityTransferFiles;','Lcom/rawwave/core/ActivityArchive;','Lcom/rawwave/core/ActivityDataExporter;','Lcom/rawwave/core/SyncAdmission;','Lcom/rawwave/core/ActivitySyncPolicy;','Lcom/rawwave/core/ActivitySyncAttemptGuard;','Lcom/rawwave/core/HelloRetryPolicy;','Lcom/rawwave/shell/ActivityTransferWindow;','Lcom/rawwave/shell/ActivityPhoneReceiveSession;')) {
            if (-not $types.Contains($required)) { throw "Missing runtime module $required in $module" }
        }
        foreach ($required in @('Lcom/rawwave/core/DiagnosticReportBuilder;','Lcom/rawwave/core/ReportExporter;','Lcom/rawwave/shell/DownloadsExportDestination;')) {
            if (-not $types.Contains($required)) { throw "Missing report module $required in $module" }
        }
        foreach ($required in @('Lcom/rawwave/shell/ProbeActivity;','Lcom/rawwave/shell/ProbeApplication;','Lcom/rawwave/core/Journal;','Lcom/rawwave/core/DiagnosticArchive;','Lcom/rawwave/shell/TransferWindow;','Lcom/rawwave/core/TransferFiles;')) {
            if (-not $types.Contains($required)) { throw "Missing DEX definition $required in $module; manifest alone does not prove wiring" }
        }
        $target = Join-Path $candidateRoot "RawWave-$module-$RunId.apk"
        Copy-Item -LiteralPath $apk -Destination $target
        $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
        $artifacts += [ordered]@{ role=$module; file=[IO.Path]::GetFileName($target); sha256=$hash; certificateSha256=$certificate; definedEntrypointsVerified=$true; debuggable=$false }
        $badging | Set-Content -LiteralPath (Join-Path $candidateRoot "$module-apk-metadata.txt") -Encoding utf8
        $manifest | Set-Content -LiteralPath (Join-Path $candidateRoot "$module-manifest.txt") -Encoding utf8
        Copy-Item -LiteralPath (Join-Path $projectRoot "$module/build/reports/lint-results-release.html") -Destination (Join-Path $candidateRoot "$module-lint.html")
    }
    if ($artifacts[0].certificateSha256 -ne $artifacts[1].certificateSha256) { throw 'Watch/phone signing certificates differ' }
# Archive every executed suite, including newly added regression tests.
$allResults = @()
$totalTests = 0
$totalSkipped = 0
foreach ($module in @('shared','wear','mobile')) {
    $resultFolder = if ($module -eq 'shared') { 'test' } else { 'testDebugUnitTest' }
    foreach ($result in Get-ChildItem (Join-Path $projectRoot "$module/build/test-results/$resultFolder") -Filter 'TEST-*.xml') {
        [xml]$suite = Get-Content -LiteralPath $result.FullName
        if ([int]$suite.testsuite.failures -gt 0 -or [int]$suite.testsuite.errors -gt 0) { throw "Failed suite: $($result.Name)" }
        $totalTests += [int]$suite.testsuite.tests
        $totalSkipped += [int]$suite.testsuite.skipped
        Copy-Item -LiteralPath $result.FullName -Destination (Join-Path $candidateRoot "$module-$($result.Name)")
        $allResults += "$module-$($result.Name)"
    }
}
$allResults | ConvertTo-Json | Set-Content (Join-Path $candidateRoot 'test-suite-index.json')
$developerResult = if ($corpusConfigured) { 'PASS' } else { 'PASS_WITH_PRIVATE_CORPUS_NOT_RUN' }
[ordered]@{ runId=$RunId; startedAt=$started.ToString('o'); completedAt=[DateTimeOffset]::Now.ToString('o'); stage='GRAVITY_GEOMAG_0_12_5'; developerResult=$developerResult; deviceResult='NOT_RUN_COMBINED_CHECK_PENDING'; privateCorpusReplay=$privateCorpusReplay; tasks=$tasks; tests=$totalTests; skippedTests=$totalSkipped; toolchain=@{agp='9.1.1';gradle='9.3.1';compileSdk=36;buildTools='36.0.0'}; signing='LOCAL_ANDROID_TEST_CERTIFICATE_NOT_PUBLIC_RELEASE'; artifacts=$artifacts; sourceInputs=$sourceAfter } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $candidateRoot 'build.json') -Encoding utf8
    Write-Output "CANDIDATE_DEV_PASS: $candidateRoot; DEVICE_NOT_RUN; privateCorpusReplay=$privateCorpusReplay"
} catch {
    [ordered]@{runId=$RunId; developerResult='FAIL'; deviceResult='NOT_RUN'; error=$_.Exception.Message} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $candidateRoot 'FAILED.json') -Encoding utf8
    throw
} finally { Pop-Location }





