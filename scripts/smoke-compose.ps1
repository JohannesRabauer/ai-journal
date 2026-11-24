# Opt-in Docker smoke test of profile A (needs Docker; not part of verify). Heavy: builds the backend image.
# 1. `docker compose config` for every profile combination
# 2. build the backend image (API and web pages in one container)
# 3. start the backend in a THROWAWAY project with a throwaway data dir and fake providers
# 4. POST and GET an entry, check the HTML pages (/, /journal, /settings: 200, h1, CSP header)
# 5. docker compose down -v and delete the temp dir (always)
# The real ./data/db is never used. Picks free host ports on 127.0.0.1.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'run-common.ps1')
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("ai-diary-smoke-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path (Join-Path $tmp 'db'), (Join-Path $tmp 'audio') | Out-Null

$env:COMPOSE_PROJECT_NAME = 'aidiarysmoke'
$env:JOURNAL_DB_DATA_DIR = (Join-Path $tmp 'db') -replace '\\', '/'
$env:JOURNAL_AUDIO_DATA_DIR = (Join-Path $tmp 'audio') -replace '\\', '/'
function Get-FreePort { $l = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0); $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop(); $p }
$bePort = Get-FreePort
$env:JOURNAL_BACKEND_HOST_PORT = "$bePort"
$env:JOURNAL_STT_PROVIDER = 'fake'; $env:JOURNAL_LLM_PROVIDER = 'fake'; $env:JOURNAL_EMBEDDING_PROVIDER = 'fake'
$env:JOURNAL_TTS_PROVIDER = 'fake'; $env:JOURNAL_VECTOR_PROVIDER = 'memory'; $env:JOURNAL_AUDIO_PROVIDER = 'memory'

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Run-Docker { & docker @args; if ($LASTEXITCODE -ne 0) { throw "docker $($args -join ' ') failed ($LASTEXITCODE)" } }

Push-Location $root
try {
    Step 'compose config for all profile combinations'
    foreach ($p in @(@(), @('--profile', 'gpu'), @('--profile', 'cpu'), @('--profile', 'cpu', '--profile', 'tts'), @('--profile', 'gpu', '--profile', 'tts'))) {
        Run-Docker compose @p config --quiet
        Write-Host "    ok: $($p -join ' ')"
    }
    Step 'build the backend image'
    Run-Docker compose build backend
    Step 'start the backend (fake providers, throwaway data)'
    Run-Docker compose up -d --wait --wait-timeout 300 backend

    Step 'POST and GET an entry through the backend'
    $created = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$bePort/api/entries" -ContentType 'application/json' -Headers @{ 'X-AI-Diary' = '1' } -Body '{"text":"smoke test entry"}'
    if (-not $created.id) { throw 'POST /api/entries returned no id' }
    $list = Invoke-RestMethod -Uri "http://127.0.0.1:$bePort/api/entries"
    if ($list.total -lt 1) { throw 'GET /api/entries does not list the entry' }
    Write-Host "    entry $($created.id) saved and listed"

    Step 'export and HTML pages'
    $zip = Join-Path $tmp 'export.zip'
    Invoke-WebRequest -Uri "http://127.0.0.1:$bePort/api/data/export" -OutFile $zip
    if ((Get-Item $zip).Length -lt 100) { throw 'export zip too small' }
    Write-Host '    export ok'
    Assert-HtmlPages "http://127.0.0.1:$bePort"
    Write-Host '==> smoke OK' -ForegroundColor Green
}
finally {
    Step 'cleanup (down -v, remove temp dir)'
    & docker compose down -v --remove-orphans 2>&1 | Out-Null
    Pop-Location
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
