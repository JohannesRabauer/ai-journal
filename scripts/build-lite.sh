#!/usr/bin/env sh
# Builds the lite-cloud artifacts (profile C): the normal backend build (one build serves every profile, D-032; lite-cloud is chosen at runtime with QUARKUS_PROFILE) into
# backend/target/lite-app, and the production frontend jar (frontend/target/app.jar).
# The Pi needs the jars only: build on a PC, then copy them (docs/PI.md).
set -eu
. "$(dirname "$0")/run-common.sh"

echo "==> Backend (output lite-app)"
mvn_run -pl backend -am package -DskipTests -Dquarkus.package.output-directory=lite-app
echo "==> Frontend (production)"
mvn_run -pl frontend -am package -DskipTests -Pproduction

need_file "$REPO_ROOT/backend/target/lite-app/quarkus-run.jar" "Lite backend build failed."
need_file "$REPO_ROOT/frontend/target/app.jar" "Frontend build failed."
echo "==> Done: backend/target/lite-app/ (directory, copy as a whole) and frontend/target/app.jar"
