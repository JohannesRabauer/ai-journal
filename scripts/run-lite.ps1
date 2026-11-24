# Profile C (lite-cloud): runs the backend (API and web pages, http://127.0.0.1:8080) with the tuned low-memory JVM flags (docs/PI.md).
# Entry text and audio are SENT TO OPENAI in this profile. It refuses to start without OPENAI_API_KEY and
# JOURNAL_CLOUD_CONSENT=I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE. Run scripts\build-lite.ps1 first.
. (Join-Path $PSScriptRoot 'run-common.ps1')
Import-DotEnv

$phrase = 'I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE'
$problems = @()
if (-not $env:OPENAI_API_KEY) { $problems += 'OPENAI_API_KEY is not set (OpenAI key used for chat, embeddings, transcription and speech).' }
if ($env:JOURNAL_CLOUD_CONSENT -ne $phrase) { $problems += "JOURNAL_CLOUD_CONSENT must be exactly $phrase." }
if ($problems.Count -gt 0) {
    Write-Host 'lite-cloud will not start:' -ForegroundColor Red
    $problems | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Host ''
    Write-Host 'What this means: in the lite-cloud profile your entry text and audio recordings are sent to OpenAI'
    Write-Host 'for transcription, questions, embeddings and speech. This relaxes the local-only privacy guarantee'
    Write-Host '(docs/PRIVACY.md). Set both values in .env only if you accept that.'
    exit 1
}

$backendJar = Join-Path $RepoRoot 'backend/target/lite-app/quarkus-run.jar'
Assert-File $backendJar 'Run scripts\build-lite.ps1 first.'

$env:QUARKUS_PROFILE = 'lite-cloud'
Set-DefaultEnv JOURNAL_STT_PROVIDER 'openai'
Set-DefaultEnv JOURNAL_LLM_PROVIDER 'openai'
Set-DefaultEnv JOURNAL_EMBEDDING_PROVIDER 'openai'
Set-DefaultEnv JOURNAL_TTS_PROVIDER 'openai'
Set-DefaultEnv JOURNAL_VECTOR_PROVIDER 'jdbc'
Set-DefaultEnv JOURNAL_AUDIO_PROVIDER 'filesystem'
$data = Join-Path $RepoRoot 'data/lite'
New-Item -ItemType Directory -Force -Path (Join-Path $data 'audio') | Out-Null
Set-DefaultEnv JOURNAL_AUDIO_FILESYSTEM_DIRECTORY (Join-Path $data 'audio')
$dbPath = (Join-Path $data 'journal') -replace '\\', '/'
Set-DefaultEnv JOURNAL_DB_URL "jdbc:h2:file:$dbPath;DB_CLOSE_ON_EXIT=FALSE"

$common = '-XX:+UseSerialGC -Xss256k -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:MaxDirectMemorySize=32m -Xms32m'
$beOpts = if ($env:LITE_BACKEND_JVM_OPTS) { $env:LITE_BACKEND_JVM_OPTS } else { "-Xmx128m -XX:MaxMetaspaceSize=96m $common" }
$java = Get-JavaExe

Write-Host "==> lite-cloud: data leaves this device (OpenAI). Starting on http://127.0.0.1:$(if ($env:JOURNAL_HTTP_PORT) { $env:JOURNAL_HTTP_PORT } else { '8080' }) (Ctrl+C stops it)."
& $java @($beOpts -split ' ') -jar $backendJar
exit $LASTEXITCODE
