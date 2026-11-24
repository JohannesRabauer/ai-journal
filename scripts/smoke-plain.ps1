# Opt-in smoke test of profile B (plain Java) with FAKE AI providers, so no Ollama, Whisper or Piper is needed. Not part of verify.
# Profile B stays local: no OpenAI, no mock-openai here.
#   1. uses a throwaway embedded H2 database file (no Docker, no database container)
#   2. starts scripts\run-backend.ps1 non-interactively (it builds first when the jar is missing); one JVM serves API and pages
#   3. checks /api/system/info (profile full, cloudMode false), the HTML pages (/, /journal, /settings: 200, h1, CSP header),
#      entry create + list, export zip, delete-all
#   4. stops everything and removes the temp dir (always)
# Needs: JDK 25 (JAVA_HOME), a free port on 127.0.0.1. The real ./data is never used.
# Usage: scripts\smoke-plain.ps1
. (Join-Path $PSScriptRoot 'run-common.ps1')

$tmp = Join-Path ([IO.Path]::GetTempPath()) ("ai-diary-smoke-plain-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path (Join-Path $tmp 'audio'), (Join-Path $tmp 'tts-cache') | Out-Null
$procs = [System.Collections.Generic.List[object]]::new()

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m) { Write-Host "    ok: $m" }
function Fail($m) { throw "SMOKE FAILED: $m" }
function Get-FreePort { $l = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0); $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop(); $p }
function Assert-That($cond, $msg) { if (-not $cond) { Fail $msg } }
function Read-Logs($h) { (Get-Content $h.Out -Raw -ErrorAction SilentlyContinue) + "`n" + (Get-Content $h.Err -Raw -ErrorAction SilentlyContinue) }
function Set-Env($name, $value) {
    if ([string]::IsNullOrEmpty($value)) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue } else { [Environment]::SetEnvironmentVariable($name, $value, 'Process') }
}
# Runs a repository script in a child shell (working directory = repo root); Stop-Tree kills the shell AND the java it started.
function Start-Script($name, $script) {
    $out = Join-Path $tmp "$name.out.log"; $err = Join-Path $tmp "$name.err.log"
    $host_ = (Get-Process -Id $PID).Path
    $p = Start-Process -FilePath $host_ -ArgumentList @('-NoProfile', '-File', (Join-Path $PSScriptRoot $script)) -WorkingDirectory $RepoRoot `
        -NoNewWindow -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    $null = $p.Handle
    $procs.Add($p)
    [pscustomobject]@{ Process = $p; Out = $out; Err = $err }
}
function Stop-Tree($p) { try { if (-not $p.HasExited) { & taskkill /PID $p.Id /T /F 2>&1 | Out-Null } } catch { } }
function Wait-Until($what, [int]$timeoutSec, [scriptblock]$cond, $h) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
        if ($h -and $h.Process.HasExited) { Fail "${what}: process exited early (code $($h.Process.ExitCode)). Log tail:`n$((Read-Logs $h) -split "`n" | Select-Object -Last 25 | Out-String)" }
        try { if (& $cond) { return $sw.Elapsed.TotalSeconds } } catch { }
        Start-Sleep -Seconds 1
    }
    Fail "${what}: not ready after $timeoutSec s"
}
function Json($o) { , [Text.Encoding]::UTF8.GetBytes(($o | ConvertTo-Json -Depth 6 -Compress)) }
function Api($method, $path, $body) {
    $a = @{ Method = $method; Uri = "$be$path"; TimeoutSec = 60 }
    if ($method -ne 'GET') { $a.Headers = @{ 'X-AI-Diary' = '1' } } # CSRF guard header, required on every non-GET request
    if ($null -ne $body) { $a.Body = Json $body; $a.ContentType = 'application/json; charset=utf-8' }
    try { Invoke-RestMethod @a } catch { Fail "$method $path -> $($_.Exception.Message) $($_.ErrorDetails.Message)" }
}

$names = 'JOURNAL_DB_URL', 'JOURNAL_HTTP_PORT', 'JOURNAL_STT_PROVIDER',
    'JOURNAL_LLM_PROVIDER', 'JOURNAL_EMBEDDING_PROVIDER', 'JOURNAL_TTS_PROVIDER', 'JOURNAL_VECTOR_PROVIDER', 'JOURNAL_AUDIO_PROVIDER',
    'JOURNAL_AUDIO_FILESYSTEM_DIRECTORY', 'JOURNAL_TTS_CACHE_DIR', 'JOURNAL_HTTP_HOST', 'QUARKUS_PROFILE', 'OPENAI_API_KEY', 'JOURNAL_CLOUD_CONSENT'
$saved = @{}
foreach ($k in $names) { $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process') }

try {
    # Clean slate: nothing from the caller's environment (or a cloud setup) may leak into profile B.
    foreach ($k in 'QUARKUS_PROFILE', 'OPENAI_API_KEY', 'JOURNAL_CLOUD_CONSENT') { Set-Env $k $null }
    $bePort = Get-FreePort
    # Fake providers: the app runs fully without AI services; vectors in the jdbc store, audio on the filesystem (as in profile B).
    Set-Env 'JOURNAL_DB_URL' ("jdbc:h2:file:" + ((Join-Path $tmp 'h2/journal') -replace '\\', '/') + ';DB_CLOSE_ON_EXIT=FALSE')
    foreach ($c in 'STT', 'LLM', 'EMBEDDING', 'TTS') { Set-Env "JOURNAL_${c}_PROVIDER" 'fake' }
    Set-Env 'JOURNAL_VECTOR_PROVIDER' 'jdbc'
    Set-Env 'JOURNAL_AUDIO_PROVIDER' 'filesystem'
    Set-Env 'JOURNAL_AUDIO_FILESYSTEM_DIRECTORY' (Join-Path $tmp 'audio')
    Set-Env 'JOURNAL_TTS_CACHE_DIR' (Join-Path $tmp 'tts-cache')
    Set-Env 'JOURNAL_HTTP_PORT' "$bePort"
    $be = "http://127.0.0.1:$bePort"

    $needsBuild = -not (Test-Path (Join-Path $RepoRoot 'backend/target/quarkus-app/quarkus-run.jar'))
    $timeout = if ($needsBuild) { 1500 } else { 240 }
    Step "start scripts\run-backend.ps1 (profile B$(if ($needsBuild) { ', builds first: be patient' }))"
    $beProc = Start-Script 'run-backend' 'run-backend.ps1'
    $beSec = Wait-Until 'backend' $timeout { Invoke-RestMethod "$be/api/system/info" -TimeoutSec 5 } $beProc
    $info = Api GET '/api/system/info'
    Assert-That ($info.profile -eq 'full') "profile is '$($info.profile)', expected full"
    Assert-That ($info.cloudMode -eq $false) 'cloudMode is true in profile B'
    Ok ("backend ready after {0:N1} s; profile={1} cloudMode={2} providers={3}" -f $beSec, $info.profile, $info.cloudMode, (($info.providers.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ','))

    Step 'HTML pages served by the same JVM'
    Assert-HtmlPages $be

    Step 'entry create/list, export, delete-all'
    $created = Api POST '/api/entries' @{ text = 'smoke test entry for profile B' }
    Assert-That ($created.id) 'POST /api/entries returned no id'
    $list = Api GET '/api/entries'
    Assert-That ($list.total -ge 1) 'GET /api/entries does not list the entry'
    Ok "entry $($created.id) saved and listed"
    $zipPath = Join-Path $tmp 'export.zip'
    Invoke-WebRequest -Uri "$be/api/data/export" -OutFile $zipPath -UseBasicParsing
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($zipPath)
    try { Assert-That ($zip.Entries | Where-Object { $_.FullName -eq 'entries.json' }) "export zip has no entries.json (has: $(($zip.Entries.FullName) -join ', '))" } finally { $zip.Dispose() }
    Ok 'export zip contains entries.json'
    $r = Invoke-WebRequest -Uri "$be/journal" -UseBasicParsing -Headers @{ 'Accept-Language' = 'en' }
    Assert-That ($r.StatusCode -eq 200 -and $r.Content -match 'smoke test entry for profile B') 'the journal page does not show the new entry'
    Ok 'journal page lists the entry'
    Api POST '/api/data/delete-all' @{ confirmation = 'DELETE EVERYTHING' } | Out-Null
    Assert-That ((Api GET '/api/entries').total -eq 0) 'journal not empty after delete-all'
    Ok 'delete-all emptied the journal'
    Write-Host '==> smoke-plain OK' -ForegroundColor Green
}
finally {
    Step 'cleanup (stop processes, remove temp dir)'
    foreach ($p in $procs) { Stop-Tree $p }
    foreach ($k in $saved.Keys) { Set-Env $k $saved[$k] }
    Start-Sleep -Milliseconds 800
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
