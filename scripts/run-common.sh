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

# assert_html_pages BASE_URL: smoke check of the server-rendered pages (D-033): 200, text/html, expected h1 and the
# Content-Security-Policy header. Asks for English so the h1 texts are known.
assert_html_pages() {
  _base="$1"
  for _spec in '/|<h1[^>]*>[[:space:]]*[^[:space:]]' '/journal|<h1[^>]*>[[:space:]]*Journal[[:space:]]*</h1>' '/settings|<h1[^>]*>[[:space:]]*Settings[[:space:]]*</h1>'; do
    _path="${_spec%%|*}"; _h1="${_spec#*|}"
    _hdr="$(mktemp)"; _body="$(mktemp)"
    _code="$(curl -sS -H 'Accept-Language: en' -D "$_hdr" -o "$_body" -w '%{http_code}' "$_base$_path")"
    [ "$_code" = "200" ] || { echo "SMOKE FAILED: $_path answered $_code" >&2; exit 1; }
    grep -qi '^content-type: text/html' "$_hdr" || { echo "SMOKE FAILED: $_path is not text/html" >&2; exit 1; }
    grep -qi "^content-security-policy:.*default-src 'self'" "$_hdr" || { echo "SMOKE FAILED: $_path has no Content-Security-Policy header" >&2; exit 1; }
    grep -Eq "$_h1" "$_body" || { echo "SMOKE FAILED: $_path has no matching h1" >&2; exit 1; }
    rm -f "$_hdr" "$_body"
    echo "    ok: GET $_path -> 200 text/html, h1, CSP"
  done
}
