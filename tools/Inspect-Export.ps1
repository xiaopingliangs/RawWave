param([Parameter(Mandatory=$true)][string[]]$Paths)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Read-ZipBytes($Entry) {
    $stream=$Entry.Open(); $memory=[IO.MemoryStream]::new()
    try { $stream.CopyTo($memory); return ,$memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
}
function Test-Bundle($Archive) {
    $manifest=([Text.Encoding]::UTF8.GetString((Read-ZipBytes $Archive.GetEntry('bundle.json'))) | ConvertFrom-Json)
    $seen=[Collections.Generic.HashSet[string]]::new()
    foreach($entry in $Archive.Entries) { if(!$seen.Add($entry.FullName)){throw "Duplicate entry: $($entry.FullName)"} }
    $count=0
    foreach($item in $manifest.files.PSObject.Properties) {
        $entry=$Archive.GetEntry($item.Name)
        if($null -eq $entry){throw "Missing: $($item.Name)"}
        $bytes=Read-ZipBytes $entry; $sha=[Security.Cryptography.SHA256]::Create()
        try { $hash=[BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-','').ToLowerInvariant() } finally { $sha.Dispose() }
        if($bytes.Length -ne $item.Value.bytes -or $hash -ne $item.Value.sha256){throw "Mismatch: $($item.Name)"}
        $count++
    }
    if($Archive.Entries.Count -ne $count+1){throw 'Unexpected entries'}
    return $count
}
foreach($path in $Paths) {
    $archive=[IO.Compression.ZipFile]::OpenRead($path)
    try {
        $count=Test-Bundle $archive
        [pscustomobject]@{file=[IO.Path]::GetFileName($path);entries=$count;sha256=(Get-FileHash -LiteralPath $path).Hash;result='PASS'} | ConvertTo-Json -Compress
        if([IO.Path]::GetFileName($path).StartsWith('rawwave-phone-')) {
            foreach($name in @('build.json','received-summary/latest.json')) {
                "$name`: $([Text.Encoding]::UTF8.GetString((Read-ZipBytes $archive.GetEntry($name))))"
            }
            foreach($name in @('phone/events.previous.jsonl','phone/events.jsonl')) {
                $content=[Text.Encoding]::UTF8.GetString((Read-ZipBytes $archive.GetEntry($name)))
                $content -split "`n" | Where-Object {$_ -match 'FILE_COMMITTED|WATCH_CONFIRM_RECEIVED|SUMMARY_SAVED|EXPORT.*COMPLETED'} | Select-Object -Last 12
            }
            foreach($entry in $archive.Entries | Where-Object FullName -match '^received-watch/.*\.zip$') {
                $memory=[IO.MemoryStream]::new((Read-ZipBytes $entry))
                $nested=[IO.Compression.ZipArchive]::new($memory,[IO.Compression.ZipArchiveMode]::Read)
                try {
                    "nested=$($entry.FullName) verifiedEntries=$(Test-Bundle $nested)"
                    foreach($name in @('transfer-info.json','watch/events.previous.jsonl','watch/events.jsonl')) {
                        $e=$nested.GetEntry($name); if(!$e){continue}
                        $text=[Text.Encoding]::UTF8.GetString((Read-ZipBytes $e))
                        if($name -eq 'transfer-info.json'){$text}
                        else {$text -split "`n" | Where-Object {$_ -match 'REMOTE_COMMIT_CONFIRMED|WATCH_SYNC_FINISHED|REPORT_SUMMARY_SAVE_CONFIRMED'} | Select-Object -Last 6}
                    }
                } finally {$nested.Dispose(); $memory.Dispose()}
            }
        }
    } finally {$archive.Dispose()}
}
