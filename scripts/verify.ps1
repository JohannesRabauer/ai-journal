# Full verification: build, unit + integration tests (H2, fakes, WireMock), frontend and backend JS tests.
# Needs no network (once Maven dependencies are cached) and no Docker.
# Docker-based smoke tests are separate and opt-in: scripts/smoke-*.ps1
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
Push-Location $root
try {
    Write-Host '==> Maven: api, backend, frontend (build + tests)'
    & (Join-Path $root 'mvnw.cmd') -B -ntp verify
    if ($LASTEXITCODE -ne 0) { throw "Maven verify failed ($LASTEXITCODE)" }

    Write-Host '==> JS unit tests (node --test)'
    & node --test 'frontend/src/test/js/**/*.test.mjs'
    if ($LASTEXITCODE -ne 0) { throw "JS tests failed ($LASTEXITCODE)" }
    & node --test 'backend/src/test/js/**/*.test.mjs'
    if ($LASTEXITCODE -ne 0) { throw "Backend JS tests failed ($LASTEXITCODE)" }

    Write-Host '==> verify OK'
}
finally {
    Pop-Location
}
