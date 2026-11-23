# Profile B: run the frontend with plain Java. The backend must already be running (scripts\run-backend.ps1).
# Usage: scripts\run-frontend.ps1 [-Build]     (first production build downloads npm packages and takes a few minutes)
# BACKEND_URL (default http://127.0.0.1:8080), JOURNAL_FRONTEND_PORT (default 8081), JOURNAL_HTTP_HOST (default 127.0.0.1).
param([switch]$Build)
. (Join-Path $PSScriptRoot 'run-common.ps1')
Import-DotEnv

$jar = Join-Path $RepoRoot 'frontend/target/app.jar'
if ($Build -or -not (Test-Path $jar)) {
    Write-Host '==> Building frontend (production)'
    Invoke-Maven @('-pl', 'frontend', '-am', 'package', '-DskipTests', '-Pproduction')
}
Assert-File $jar 'Build failed?'
$port = if ($env:JOURNAL_FRONTEND_PORT) { $env:JOURNAL_FRONTEND_PORT } else { '8081' }
Write-Host "==> Starting frontend on http://127.0.0.1:$port (backend: $(if ($env:BACKEND_URL) { $env:BACKEND_URL } else { 'http://127.0.0.1:8080' }))"
& (Get-JavaExe) -jar $jar
exit $LASTEXITCODE
