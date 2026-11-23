#!/usr/bin/env sh
# Full verification: build, unit + integration tests (H2, fakes, WireMock), frontend JS tests.
# Needs no network (once Maven dependencies are cached) and no Docker.
# Docker-based smoke tests are separate and opt-in: scripts/smoke-*.sh
set -eu
cd "$(dirname "$0")/.."

echo '==> Maven: api, backend, frontend (build + tests)'
./mvnw -B -ntp verify

echo '==> JS unit tests (node --test)'
node --test 'frontend/src/test/js/**/*.test.mjs'

echo '==> verify OK'
