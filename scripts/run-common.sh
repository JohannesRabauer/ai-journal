#!/usr/bin/env sh
# Shared helpers for scripts/run-*.sh (source this file). Not meant to be run directly.
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Loads KEY=VALUE lines from .env; variables already set in the environment win.
import_dotenv() {
  f="${1:-$REPO_ROOT/.env}"
  [ -f "$f" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line="$(printf '%s' "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/\r$//')"
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *=*) ;; *) continue ;; esac
    k="${line%%=*}"
    v="${line#*=}"
    v="$(printf '%s' "$v" | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/")"
    eval "cur=\${$k:-}"
    if [ -z "$cur" ]; then
      export "$k=$v"
    fi
  done < "$f"
}

# set_default NAME VALUE
set_default() {
  eval "cur=\${$1:-}"
  if [ -z "$cur" ]; then
    export "$1=$2"
  fi
}

java_bin() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    printf '%s' "$JAVA_HOME/bin/java"
  elif command -v java >/dev/null 2>&1; then
    command -v java
  else
    echo "Java 25 not found. Install a JDK 25 (Temurin) and set JAVA_HOME." >&2
    exit 1
  fi
}

mvn_run() {
  "$REPO_ROOT/mvnw" -B -ntp "$@"
}

need_file() {
  [ -f "$1" ] || { echo "Missing $1. $2" >&2; exit 1; }
}
