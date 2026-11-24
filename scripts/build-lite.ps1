# Builds the lite-cloud artifacts (profile C): the normal backend build (one build serves every profile, D-032; lite-cloud is chosen at runtime with QUARKUS_PROFILE) into
# backend/target/lite-app (REST API and web pages in one jar directory).
# The Pi needs that directory only: build on a PC, then copy it (docs/PI.md).
. (Join-Path $PSScriptRoot 'run-common.ps1')

Write-Host '==> Backend (output lite-app)'
Invoke-Maven @('-pl', 'backend', '-am', 'package', '-DskipTests', '-Dquarkus.package.output-directory=lite-app')

Assert-File (Join-Path $RepoRoot 'backend/target/lite-app/quarkus-run.jar') 'Lite backend build failed.'
Write-Host '==> Done: backend/target/lite-app/ (directory, copy as a whole)'
