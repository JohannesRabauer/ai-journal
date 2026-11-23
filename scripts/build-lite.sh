#!/usr/bin/env sh
# Builds the lite-cloud artifacts (profile C): backend with the H2 datasource (build-time decision, D-007) into
# backend/target/lite-app, and the production frontend jar (frontend/target/app.jar).
# The Pi needs the jars only: build on a PC, then copy them (docs/PI.md).
set -eu
. "$(dirname "$0")/run-common.sh"

echo "==> Backend (-Dquarkus.profile=lite-cloud, output lite-app)"
mvn_run -pl backend -am package -DskipTests -Dquarkus.profile=lite-cloud -Dquarkus.package.output-directory=lite-app
echo "==> Frontend (production)"
mvn_run -pl frontend -am package -DskipTests -Pproduction

need_file "$REPO_ROOT/backend/target/lite-app/quarkus-run.jar" "Lite backend build failed."
need_file "$REPO_ROOT/frontend/target/app.jar" "Frontend build failed."
echo "==> Done: backend/target/lite-app/ (directory, copy as a whole) and frontend/target/app.jar"
