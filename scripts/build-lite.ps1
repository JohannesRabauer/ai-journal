# Builds the lite-cloud artifacts (profile C): backend with the H2 datasource (build-time decision, D-007) into
# backend/target/lite-app, and the production frontend jar (frontend/target/app.jar).
# The Pi needs the jars only: build on a PC, then copy them (docs/PI.md).
. (Join-Path $PSScriptRoot 'run-common.ps1')

Write-Host '==> Backend (-Dquarkus.profile=lite-cloud, output lite-app)'
Invoke-Maven @('-pl', 'backend', '-am', 'package', '-DskipTests', '-Dquarkus.profile=lite-cloud', '-Dquarkus.package.output-directory=lite-app')
Write-Host '==> Frontend (production)'
Invoke-Maven @('-pl', 'frontend', '-am', 'package', '-DskipTests', '-Pproduction')

Assert-File (Join-Path $RepoRoot 'backend/target/lite-app/quarkus-run.jar') 'Lite backend build failed.'
Assert-File (Join-Path $RepoRoot 'frontend/target/app.jar') 'Frontend build failed.'
Write-Host '==> Done: backend/target/lite-app/ (directory, copy as a whole) and frontend/target/app.jar'
