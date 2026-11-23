# Profile B: run the backend with plain Java against services you run yourself (Postgres, Ollama, Whisper, Piper).
# Usage: scripts\run-backend.ps1 [-Build]      (-Build forces a Maven build; it also runs when the jar is missing)
# Reads .env in the repository root; variables already set in the environment win. See docs/CONFIG.md.
param([switch]$Build)
. (Join-Path $PSScriptRoot 'run-common.ps1')
Import-DotEnv

if (-not $env:JOURNAL_DB_PASSWORD) {
    throw 'JOURNAL_DB_PASSWORD is not set. Copy .env.example to .env and set it (the database user is ai-diary).'
}
# Provider defaults for profile B (full local). Override any of them in .env or the environment.
Set-DefaultEnv JOURNAL_STT_PROVIDER 'whisper'
Set-DefaultEnv JOURNAL_LLM_PROVIDER 'ollama'
Set-DefaultEnv JOURNAL_EMBEDDING_PROVIDER 'ollama'
Set-DefaultEnv JOURNAL_VECTOR_PROVIDER 'pgvector'
Set-DefaultEnv JOURNAL_AUDIO_PROVIDER 'filesystem'
Set-DefaultEnv JOURNAL_TTS_PROVIDER 'piper'
Set-DefaultEnv JOURNAL_DB_URL 'jdbc:postgresql://localhost:15432/ai-diary'
Set-DefaultEnv JOURNAL_STT_WHISPER_BASE_URL 'http://localhost:8000'
Set-DefaultEnv JOURNAL_LLM_OLLAMA_BASE_URL 'http://localhost:11434'
Set-DefaultEnv JOURNAL_EMBEDDING_OLLAMA_BASE_URL 'http://localhost:11434'
Set-DefaultEnv JOURNAL_TTS_PIPER_BASE_URL 'http://localhost:5000'
Set-DefaultEnv JOURNAL_AUDIO_FILESYSTEM_DIRECTORY (Join-Path $RepoRoot 'data/audio')
New-Item -ItemType Directory -Force -Path $env:JOURNAL_AUDIO_FILESYSTEM_DIRECTORY | Out-Null

$jar = Join-Path $RepoRoot 'backend/target/quarkus-app/quarkus-run.jar'
if ($Build -or -not (Test-Path $jar)) {
    Write-Host '==> Building backend'
    Invoke-Maven @('-pl', 'backend', '-am', 'package', '-DskipTests')
}
Assert-File $jar 'Build failed?'
Write-Host "==> Starting backend on http://127.0.0.1:$(if ($env:JOURNAL_HTTP_PORT) { $env:JOURNAL_HTTP_PORT } else { '8080' })"
& (Get-JavaExe) -jar $jar
exit $LASTEXITCODE
