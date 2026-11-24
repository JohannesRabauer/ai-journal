# Opt-in smoke test of profile C (lite-cloud) against a MOCKED OpenAI API (scripts/mock-openai). Not part of verify.
# Nothing leaves this machine: journal.openai.base-url points to a local mock, the API key is a dummy.
#   a) startup is refused without consent and without an API key (clear message)
#   b) with both: /api/system/info says lite-cloud + cloudMode, the frontend renders the cloud banner (headless Edge/Chrome)
#   c) text entry -> finish -> coach conversation + answer -> memory extraction -> gym exercise (personalized) + feedback -> weekly report
#   d) /api/transcribe (WAV) returns the mock text, /api/tts returns audio
#   e) export zip contains entries.json, delete-all empties the journal
#   f) memory of both JVMs (Windows working set, NOT the Linux RSS of docs/PI.md; use it as a rough guide only)
# Needs: JDK 25 (JAVA_HOME), Node 18+. Builds with scripts\build-lite.ps1 unless -SkipBuild. Throwaway data dir and free ports on 127.0.0.1.
# Usage: scripts\smoke-lite.ps1 [-SkipBuild]
param([switch]$SkipBuild)
. (Join-Path $PSScriptRoot 'run-common.ps1')

$phrase = 'I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE'
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("ai-diary-smoke-lite-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tmp | Out-Null
$procs = [System.Collections.Generic.List[object]]::new()
$summary = [ordered]@{}

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m) { Write-Host "    ok: $m" }
function Fail($m) { throw "SMOKE FAILED: $m" }
function Get-FreePort { $l = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0); $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop(); $p }
function Assert-That($cond, $msg) { if (-not $cond) { Fail $msg } }

# Starts a process with its working directory in the repository (never elsewhere), output to files in $tmp.
function Start-Proc($name, $exe, [string[]]$argList) {
    $out = Join-Path $tmp "$name.out.log"; $err = Join-Path $tmp "$name.err.log"
    $p = Start-Process -FilePath $exe -ArgumentList $argList -WorkingDirectory $RepoRoot -NoNewWindow -PassThru `
        -RedirectStandardOutput $out -RedirectStandardError $err
    $null = $p.Handle # keeps the handle so ExitCode is readable after exit
    $procs.Add($p)
    [pscustomobject]@{ Process = $p; Out = $out; Err = $err }
}
function Read-Logs($h) { (Get-Content $h.Out -Raw -ErrorAction SilentlyContinue) + "`n" + (Get-Content $h.Err -Raw -ErrorAction SilentlyContinue) }
function Wait-Until($what, [int]$timeoutSec, [scriptblock]$cond, $h) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
        if ($h -and $h.Process.HasExited) { Fail "${what}: process exited early (code $($h.Process.ExitCode)). Log tail:`n$((Read-Logs $h) -split "`n" | Select-Object -Last 25 | Out-String)" }
        try { if (& $cond) { return $sw.Elapsed.TotalSeconds } } catch { }
        Start-Sleep -Milliseconds 500
    }
    Fail "${what}: not ready after $timeoutSec s"
}
function Json($o) { , [Text.Encoding]::UTF8.GetBytes(($o | ConvertTo-Json -Depth 6 -Compress)) }
function Api($method, $path, $body) {
    $a = @{ Method = $method; Uri = "$be$path"; TimeoutSec = 120 }
    if ($method -ne 'GET') { $a.Headers = @{ 'X-AI-Diary' = '1' } } # CSRF guard header, required on every non-GET request
    if ($null -ne $body) { $a.Body = Json $body; $a.ContentType = 'application/json; charset=utf-8' }
    try { Invoke-RestMethod @a }
    catch { Fail "$method $path -> $($_.Exception.Message) $($_.ErrorDetails.Message)" }
}
function Find-Browser {
    foreach ($c in @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe", "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
            "$env:ProgramFiles\Google\Chrome\Application\chrome.exe", "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe")) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    $null
}
function Get-Mb($p) { $p.Refresh(); [math]::Round($p.WorkingSet64 / 1MB) }

$common = '-XX:+UseSerialGC -Xss256k -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:MaxDirectMemorySize=32m -Xms32m'
$beOpts = @("-Xmx128m -XX:MaxMetaspaceSize=96m $common" -split ' ')
$feOpts = @("-Xmx160m -XX:MaxMetaspaceSize=128m $common" -split ' ')
$java = Get-JavaExe
$beJar = Join-Path $RepoRoot 'backend/target/lite-app/quarkus-run.jar'
$feJar = Join-Path $RepoRoot 'frontend/target/app.jar'
$saved = @{}
foreach ($k in 'QUARKUS_PROFILE', 'OPENAI_API_KEY', 'JOURNAL_CLOUD_CONSENT', 'JOURNAL_OPENAI_BASE_URL', 'JOURNAL_DB_URL', 'JOURNAL_HTTP_PORT', 'JOURNAL_STT_PROVIDER',
    'JOURNAL_LLM_PROVIDER', 'JOURNAL_EMBEDDING_PROVIDER', 'JOURNAL_TTS_PROVIDER', 'JOURNAL_VECTOR_PROVIDER', 'JOURNAL_AUDIO_PROVIDER',
    'JOURNAL_AUDIO_FILESYSTEM_DIRECTORY', 'JOURNAL_TTS_CACHE_DIR', 'BACKEND_URL', 'JOURNAL_FRONTEND_PORT', 'JOURNAL_HTTP_HOST') {
    $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process')
}
function Set-Env($name, $value) {
    # An empty value must REMOVE the variable (an empty string would be read by Quarkus as a defined, empty property).
    if ([string]::IsNullOrEmpty($value)) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue } else { [Environment]::SetEnvironmentVariable($name, $value, 'Process') }
}
function Reset-LiteEnv([string]$consent, [string]$key) {
    # Start from a clean slate so ambient variables (or a .env loaded elsewhere) cannot leak into the checks.
    foreach ($k in $saved.Keys) { Set-Env $k $null }
    Set-Env 'QUARKUS_PROFILE' 'lite-cloud'
    if ($consent) { Set-Env 'JOURNAL_CLOUD_CONSENT' $consent }
    if ($key) { Set-Env 'OPENAI_API_KEY' $key }
    Set-Env 'JOURNAL_OPENAI_BASE_URL' $mockUrl
    Set-Env 'JOURNAL_DB_URL' ("jdbc:h2:file:" + ((Join-Path $tmp 'h2/journal') -replace '\\', '/') + ';DB_CLOSE_ON_EXIT=FALSE')
    Set-Env 'JOURNAL_AUDIO_FILESYSTEM_DIRECTORY' (Join-Path $tmp 'audio')
    Set-Env 'JOURNAL_TTS_CACHE_DIR' (Join-Path $tmp 'tts-cache')
    foreach ($c in 'STT', 'LLM', 'EMBEDDING', 'TTS') { Set-Env "JOURNAL_${c}_PROVIDER" 'openai' }
    Set-Env 'JOURNAL_VECTOR_PROVIDER' 'jdbc'
    Set-Env 'JOURNAL_AUDIO_PROVIDER' 'filesystem'
}

try {
    if (-not $SkipBuild) { Step 'build lite artifacts (scripts\build-lite.ps1)'; & (Join-Path $PSScriptRoot 'build-lite.ps1') }
    Assert-File $beJar 'Run scripts\build-lite.ps1 first.'
    Assert-File $feJar 'Run scripts\build-lite.ps1 first.'
    New-Item -ItemType Directory -Force -Path (Join-Path $tmp 'h2'), (Join-Path $tmp 'audio') | Out-Null
    $node = (Get-Command node -ErrorAction SilentlyContinue)
    if (-not $node) { Fail 'node not found (Node 18+ needed for the OpenAI mock)' }

    Step 'start the OpenAI mock (127.0.0.1, dummy key)'
    $mockPort = Get-FreePort
    $mock = Start-Proc 'mock' $node.Source @((Join-Path $PSScriptRoot 'mock-openai/server.mjs'), '--port', "$mockPort")
    $mockUrl = "http://127.0.0.1:$mockPort/v1"
    Wait-Until 'mock' 20 { (Get-Content $mock.Out -Raw -ErrorAction SilentlyContinue) -match 'MOCK_OPENAI_LISTENING' } $mock | Out-Null
    Ok $mockUrl

    # ---------------------------------------------------------------- (a) refusals
    Step '(a) startup is refused without consent / without API key'
    foreach ($case in @(
            @{ Name = 'no-consent'; Consent = $null; Key = 'sk-smoke-dummy'; Expect = 'JOURNAL_CLOUD_CONSENT' },
            @{ Name = 'no-key'; Consent = $phrase; Key = $null; Expect = 'OPENAI_API_KEY' })) {
        Reset-LiteEnv $case.Consent $case.Key
        Set-Env 'JOURNAL_HTTP_PORT' "$(Get-FreePort)"
        $h = Start-Proc "refuse-$($case.Name)" $java ($beOpts + @('-jar', $beJar))
        if (-not $h.Process.WaitForExit(120000)) { Fail "backend $($case.Name) kept running instead of refusing to start" }
        Assert-That ($h.Process.ExitCode -ne 0) "backend $($case.Name) exited with code 0"
        $log = Read-Logs $h
        Assert-That ($log -match $case.Expect) "refusal message for $($case.Name) does not mention $($case.Expect). Log tail:`n$(($log -split "`n" | Select-Object -Last 15) -join "`n")"
        Ok "$($case.Name): exit code $($h.Process.ExitCode), message mentions $($case.Expect)"
    }

    # ---------------------------------------------------------------- (b) start both
    Step '(b) start backend + frontend with consent and key (PI.md JVM flags)'
    Reset-LiteEnv $phrase 'sk-smoke-dummy'
    $bePort = Get-FreePort; $fePort = Get-FreePort
    Set-Env 'JOURNAL_HTTP_PORT' "$bePort"
    $be = "http://127.0.0.1:$bePort"
    $beProc = Start-Proc 'backend' $java ($beOpts + @('-jar', $beJar))
    $summary.backendStartSec = [math]::Round((Wait-Until 'backend' 240 { (Invoke-RestMethod "$be/api/system/info" -TimeoutSec 5) } $beProc), 1)
    Set-Env 'BACKEND_URL' $be; Set-Env 'JOURNAL_FRONTEND_PORT' "$fePort"; Set-Env 'JOURNAL_HTTP_HOST' '127.0.0.1'
    $feProc = Start-Proc 'frontend' $java ($feOpts + @('-jar', $feJar))
    $fe = "http://127.0.0.1:$fePort"
    $summary.frontendStartSec = [math]::Round((Wait-Until 'frontend' 240 { (Invoke-WebRequest "$fe/" -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200 } $feProc), 1)
    $info = Api GET '/api/system/info'
    Assert-That ($info.profile -eq 'lite-cloud') "profile is '$($info.profile)', expected lite-cloud"
    Assert-That ($info.cloudMode -eq $true) 'cloudMode is not true'
    Ok "backend $($summary.backendStartSec) s, frontend $($summary.frontendStartSec) s; profile=$($info.profile) cloudMode=$($info.cloudMode) providers=$(($info.providers.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ',')"

    $browser = Find-Browser
    if ($browser) {
        $profileDir = Join-Path $tmp 'browser-profile'
        $dom = & $browser --headless=new --disable-gpu --no-first-run "--user-data-dir=$profileDir" --virtual-time-budget=20000 --dump-dom "$fe/" 2>$null | Out-String
        Assert-That ($dom -match 'Cloud-Modus ist aktiv|Cloud mode is active') 'frontend page does not show the cloud banner (headless browser DOM)'
        Ok 'frontend renders the cloud banner'
    }
    else { Write-Host '    SKIPPED: no Edge/Chrome found, cloud banner not checked' -ForegroundColor Yellow }

    # ---------------------------------------------------------------- (c) AI features through the mock
    Step '(c) entry, coach, memory, gym, weekly report'
    $text = 'Heute war ein langer Tag im Buero und ich habe viel ueber meine Arbeit nachgedacht. Ich habe mir vorgenommen, morgen frueher anzufangen und die wichtigste Aufgabe zuerst zu erledigen.'
    $entry = Api POST '/api/entries' @{ text = $text; language = 'DE'; source = 'TEXT' }
    Assert-That ($entry.id) 'POST /api/entries returned no id'
    $entry = Api POST "/api/entries/$($entry.id)/finish"
    Assert-That ($entry.status -eq 'FINISHED') "entry status is $($entry.status), expected FINISHED"
    Ok "entry $($entry.id) created and finished"
    $conv = Api POST '/api/conversations' @{ entryId = $entry.id }
    Assert-That (@($conv.messages | Where-Object { $_.kind -eq 'OPENING_QUESTION' }).Count -ge 1) 'coach conversation has no opening question'
    $conv = Api POST "/api/conversations/$($conv.id)/answers" @{ text = 'Ich moechte morgen frueher anfangen und mehr Pausen machen.'; voice = $false }
    $coachMsgs = @($conv.messages | Where-Object { $_.role -eq 'COACH' })
    Assert-That (@($coachMsgs | Where-Object { $_.kind -ne 'OPENING_QUESTION' }).Count -ge 1) "coach did not reply to the answer (coach messages: $($coachMsgs.Count))"
    Ok "coach conversation $($conv.id): $($conv.messages.Count) messages, reply via mock"
    $cands = @(Api POST '/api/memory/extractions' @{ sourceType = 'ENTRY'; sourceId = $entry.id })
    Assert-That ($cands.Count -ge 1) 'memory extraction returned no candidates'
    Ok "memory extraction: $($cands.Count) candidate(s)"
    $ex = Api POST '/api/gym/exercises'
    Assert-That ($ex.personalized -eq $true) "gym exercise is not personalized (title '$($ex.title)'); the mock LLM path was not used"
    $att = Api POST "/api/gym/exercises/$($ex.id)/attempts" @{ answer = 'Ich nehme an, dass frueher anfangen mir hilft.'; durationSeconds = 120 }
    $att = Api POST "/api/gym/attempts/$($att.id)/feedback"
    Assert-That (@($att.feedbackQuestions).Count -ge 1) 'gym feedback returned no questions'
    Ok "gym exercise $($ex.id) personalized, feedback question stored"
    $now = Get-Date
    $report = Api POST '/api/weekly-reports' @{ isoYear = [Globalization.ISOWeek]::GetYear($now); isoWeek = [Globalization.ISOWeek]::GetWeekOfYear($now) }
    Assert-That (@($report.recurringThemes).Count -ge 2 -and @($report.inYourOwnWords).Count -ge 2) 'weekly report is incomplete'
    Ok "weekly report $($report.id) for $($report.isoYear)-W$($report.isoWeek)"

    # ---------------------------------------------------------------- (d) audio
    Step '(d) transcribe and tts'
    $wav = [byte[]]::new(44 + 1600)
    [Text.Encoding]::ASCII.GetBytes('RIFF').CopyTo($wav, 0); [BitConverter]::GetBytes([int](36 + 1600)).CopyTo($wav, 4)
    [Text.Encoding]::ASCII.GetBytes('WAVEfmt ').CopyTo($wav, 8); [BitConverter]::GetBytes([int]16).CopyTo($wav, 16)
    [BitConverter]::GetBytes([int16]1).CopyTo($wav, 20); [BitConverter]::GetBytes([int16]1).CopyTo($wav, 22)
    [BitConverter]::GetBytes([int]8000).CopyTo($wav, 24); [BitConverter]::GetBytes([int]16000).CopyTo($wav, 28)
    [BitConverter]::GetBytes([int16]2).CopyTo($wav, 32); [BitConverter]::GetBytes([int16]16).CopyTo($wav, 34)
    [Text.Encoding]::ASCII.GetBytes('data').CopyTo($wav, 36); [BitConverter]::GetBytes([int]1600).CopyTo($wav, 40)
    $tr = Invoke-RestMethod -Method Post -Uri "$be/api/transcribe?language=DE" -ContentType 'audio/wav' -Headers @{ 'X-AI-Diary' = '1' } -Body $wav -TimeoutSec 60
    Assert-That ($tr.text -eq 'Das ist ein Test der Spracherkennung.') "transcribe returned '$($tr.text)'"
    Ok "transcribe: '$($tr.text)'"
    $tts = Invoke-WebRequest -Method Post -Uri "$be/api/tts" -Headers @{ 'X-AI-Diary' = '1' } -ContentType 'application/json' -Body (Json @{ text = 'Hallo Welt.'; language = 'DE' }) -UseBasicParsing -TimeoutSec 60
    Assert-That ($tts.StatusCode -eq 200 -and "$($tts.Headers['Content-Type'])" -like 'audio/*' -and $tts.Content.Length -gt 100) "tts answered $($tts.StatusCode) $($tts.Headers['Content-Type'])"
    Ok "tts: $($tts.Headers['Content-Type']), $($tts.Content.Length) bytes"

    # ---------------------------------------------------------------- frontend sessions (memory realism) then (f) before delete
    foreach ($route in '', 'settings', 'entries') { try { Invoke-WebRequest "$fe/$route" -UseBasicParsing -TimeoutSec 20 | Out-Null } catch { } }
    $summary.backendWorkingSetMb = Get-Mb $beProc.Process
    $summary.frontendWorkingSetMb = Get-Mb $feProc.Process

    # ---------------------------------------------------------------- (e) export and delete
    Step '(e) export and delete-all'
    $zipPath = Join-Path $tmp 'export.zip'
    Invoke-WebRequest -Uri "$be/api/data/export" -OutFile $zipPath -UseBasicParsing
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($zipPath)
    try { Assert-That (($zip.Entries | Where-Object { $_.FullName -eq 'entries.json' })) "export zip has no entries.json (has: $(($zip.Entries.FullName) -join ', '))" } finally { $zip.Dispose() }
    Ok 'export zip contains entries.json'
    Api POST '/api/data/delete-all' @{ confirmation = 'DELETE EVERYTHING' } | Out-Null
    $list = Api GET '/api/entries'
    Assert-That ($list.total -eq 0) "journal not empty after delete-all (total $($list.total))"
    Ok 'delete-all emptied the journal'

    # ---------------------------------------------------------------- (f) memory
    Step '(f) memory of both JVMs'
    Write-Host ("    backend  working set {0} MB (heap limit 128m)" -f $summary.backendWorkingSetMb)
    Write-Host ("    frontend working set {0} MB (heap limit 160m)" -f $summary.frontendWorkingSetMb)
    Write-Host ("    total    {0} MB  [Windows WorkingSet64, includes shared DLLs; PI.md budget of 750 MB is Linux RSS - indicative only]" -f ($summary.backendWorkingSetMb + $summary.frontendWorkingSetMb))
    Write-Host ("    startup: backend {0} s, frontend {1} s" -f $summary.backendStartSec, $summary.frontendStartSec)
    Write-Host '==> smoke-lite OK' -ForegroundColor Green
}
finally {
    Step 'cleanup (stop processes, remove temp dir)'
    foreach ($p in $procs) { try { if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force } } catch { } }
    foreach ($k in $saved.Keys) { Set-Env $k $saved[$k] }
    Start-Sleep -Milliseconds 800
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
