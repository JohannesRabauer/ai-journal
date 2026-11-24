# Opt-in smoke test of profile B (plain Java) with FAKE AI providers, so no Ollama, Whisper or Piper is needed. Not part of verify.
# Profile B stays local: no OpenAI, no mock-openai here.
#   1. starts ONLY Postgres (pgvector) from docker-compose.yml in a throwaway compose project with a throwaway data dir
#   2. starts scripts\run-backend.ps1 and scripts\run-frontend.ps1 non-interactively (they build first when a jar is missing)
#   3. checks /api/system/info (profile full, cloudMode false), entry create + list, frontend 200, export zip, delete-all
#   4. stops everything and removes the container, volume and temp dir (always)
# Needs: JDK 25 (JAVA_HOME), Docker, free ports on 127.0.0.1. The real ./db/data and ./data are never used.
# Usage: scripts\smoke-plain.ps1
. (Join-Path $PSScriptRoot 'run-common.ps1')

$tmp = Join-Path ([IO.Path]::GetTempPath()) ("ai-diary-smoke-plain-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path (Join-Path $tmp 'db'), (Join-Path $tmp 'audio'), (Join-Path $tmp 'tts-cache') | Out-Null
$procs = [System.Collections.Generic.List[object]]::new()
$composeStarted = $false

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

$names = 'COMPOSE_PROJECT_NAME', 'JOURNAL_DB_PASSWORD', 'JOURNAL_DB_DATA_DIR', 'JOURNAL_DB_HOST_PORT', 'JOURNAL_DB_URL', 'JOURNAL_HTTP_PORT', 'JOURNAL_STT_PROVIDER',
    'JOURNAL_LLM_PROVIDER', 'JOURNAL_EMBEDDING_PROVIDER', 'JOURNAL_TTS_PROVIDER', 'JOURNAL_VECTOR_PROVIDER', 'JOURNAL_AUDIO_PROVIDER',
    'JOURNAL_AUDIO_FILESYSTEM_DIRECTORY', 'JOURNAL_TTS_CACHE_DIR', 'BACKEND_URL', 'JOURNAL_FRONTEND_PORT', 'JOURNAL_HTTP_HOST', 'QUARKUS_PROFILE', 'OPENAI_API_KEY', 'JOURNAL_CLOUD_CONSENT'
$saved = @{}
foreach ($k in $names) { $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process') }

try {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) { Fail 'docker not found (only the Postgres container is needed)' }
    # Clean slate: nothing from the caller's environment (or a cloud setup) may leak into profile B.
    foreach ($k in 'QUARKUS_PROFILE', 'OPENAI_API_KEY', 'JOURNAL_CLOUD_CONSENT') { Set-Env $k $null }
    $dbPort = Get-FreePort; $bePort = Get-FreePort; $fePort = Get-FreePort
    $dbPassword = [Guid]::NewGuid().ToString('N')
    Set-Env 'COMPOSE_PROJECT_NAME' 'aidiarysmokeplain'
    Set-Env 'JOURNAL_DB_PASSWORD' $dbPassword
    Set-Env 'JOURNAL_DB_DATA_DIR' ((Join-Path $tmp 'db') -replace '\\', '/')
    Set-Env 'JOURNAL_DB_HOST_PORT' "$dbPort"

    Step 'start Postgres only (docker compose db, throwaway project and data dir)'
    Push-Location $RepoRoot
    try {
        $composeStarted = $true
        & docker compose up -d --build --wait --wait-timeout 300 db
        if ($LASTEXITCODE -ne 0) { Fail "docker compose up db failed ($LASTEXITCODE)" }
    }
    finally { Pop-Location }
    Ok "postgres on 127.0.0.1:$dbPort"

    # Fake providers: the app runs fully without AI services; vectors in pgvector, audio on the filesystem (as in profile B).
    Set-Env 'JOURNAL_DB_URL' "jdbc:postgresql://127.0.0.1:$dbPort/ai-diary"
    foreach ($c in 'STT', 'LLM', 'EMBEDDING', 'TTS') { Set-Env "JOURNAL_${c}_PROVIDER" 'fake' }
    Set-Env 'JOURNAL_VECTOR_PROVIDER' 'pgvector'
    Set-Env 'JOURNAL_AUDIO_PROVIDER' 'filesystem'
    Set-Env 'JOURNAL_AUDIO_FILESYSTEM_DIRECTORY' (Join-Path $tmp 'audio')
    Set-Env 'JOURNAL_TTS_CACHE_DIR' (Join-Path $tmp 'tts-cache')
    Set-Env 'JOURNAL_HTTP_PORT' "$bePort"
    $be = "http://127.0.0.1:$bePort"
    $fe = "http://127.0.0.1:$fePort"

    $needsBuild = -not (Test-Path (Join-Path $RepoRoot 'backend/target/quarkus-app/quarkus-run.jar')) -or -not (Test-Path (Join-Path $RepoRoot 'frontend/target/app.jar'))
    $timeout = if ($needsBuild) { 1500 } else { 240 }
    Step "start scripts\run-backend.ps1 (profile B$(if ($needsBuild) { ', builds first: be patient' }))"
    $beProc = Start-Script 'run-backend' 'run-backend.ps1'
    $beSec = Wait-Until 'backend' $timeout { Invoke-RestMethod "$be/api/system/info" -TimeoutSec 5 } $beProc
    $info = Api GET '/api/system/info'
    Assert-That ($info.profile -eq 'full') "profile is '$($info.profile)', expected full"
    Assert-That ($info.cloudMode -eq $false) 'cloudMode is true in profile B'
    Ok ("backend ready after {0:N1} s; profile={1} cloudMode={2} providers={3}" -f $beSec, $info.profile, $info.cloudMode, (($info.providers.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ','))

    Step 'start scripts\run-frontend.ps1'
    Set-Env 'BACKEND_URL' $be; Set-Env 'JOURNAL_FRONTEND_PORT' "$fePort"; Set-Env 'JOURNAL_HTTP_HOST' '127.0.0.1'
    Set-Env 'JOURNAL_HTTP_PORT' $null # the frontend must not inherit the backend port
    $feProc = Start-Script 'run-frontend' 'run-frontend.ps1'
    $feSec = Wait-Until 'frontend' $timeout { (Invoke-WebRequest "$fe/" -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200 } $feProc
    Ok ("frontend answers 200 after {0:N1} s" -f $feSec)

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
    $r = Invoke-WebRequest -Uri "$fe/" -UseBasicParsing
    Assert-That ($r.StatusCode -eq 200) "frontend answered $($r.StatusCode)"
    Api POST '/api/data/delete-all' @{ confirmation = 'DELETE EVERYTHING' } | Out-Null
    Assert-That ((Api GET '/api/entries').total -eq 0) 'journal not empty after delete-all'
    Ok 'delete-all emptied the journal'
    Write-Host '==> smoke-plain OK' -ForegroundColor Green
}
finally {
    Step 'cleanup (stop processes and container, remove temp dir)'
    foreach ($p in $procs) { Stop-Tree $p }
    if ($composeStarted) {
        Push-Location $RepoRoot
        try { & docker compose down -v --remove-orphans 2>&1 | Out-Null } finally { Pop-Location }
    }
    foreach ($k in $saved.Keys) { Set-Env $k $saved[$k] }
    Start-Sleep -Milliseconds 800
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
