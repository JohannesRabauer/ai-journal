#!/usr/bin/env sh
# Full verification: build, unit + integration tests (H2, fakes, WireMock) and the browser-module JS tests of the backend.
# Needs no network (once Maven dependencies are cached) and no Docker.
# Docker-based smoke tests are separate and opt-in: scripts/smoke-*.sh
set -eu
cd "$(dirname "$0")/.."

echo '==> Maven: api, backend (build + tests)'
./mvnw -B -ntp verify

echo '==> JS unit tests (node --test)'
node --test 'backend/src/test/js/**/*.test.mjs'

echo '==> verify OK'
