# Builds the lite-cloud artifacts (profile C): the normal backend build (one build serves every profile, D-032; lite-cloud is chosen at runtime with QUARKUS_PROFILE) into
# backend/target/lite-app, and the production frontend jar (frontend/target/app.jar).
# The Pi needs the jars only: build on a PC, then copy them (docs/PI.md).
. (Join-Path $PSScriptRoot 'run-common.ps1')

Write-Host '==> Backend (output lite-app)'
Invoke-Maven @('-pl', 'backend', '-am', 'package', '-DskipTests', '-Dquarkus.package.output-directory=lite-app')
Write-Host '==> Frontend (production)'
Invoke-Maven @('-pl', 'frontend', '-am', 'package', '-DskipTests', '-Pproduction')

Assert-File (Join-Path $RepoRoot 'backend/target/lite-app/quarkus-run.jar') 'Lite backend build failed.'
Assert-File (Join-Path $RepoRoot 'frontend/target/app.jar') 'Frontend build failed.'
Write-Host '==> Done: backend/target/lite-app/ (directory, copy as a whole) and frontend/target/app.jar'
