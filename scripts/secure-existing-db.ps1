# Secures a PostgreSQL database that was created before v1 with "trust" authentication (no password needed):
#   1. backs the database up (pg_dump, custom format) into -BackupDir,
#   2. sets the password of the ai-diary user to JOURNAL_DB_PASSWORD (from .env or the environment),
#   3. switches every "trust" rule in pg_hba.conf to scram-sha-256 (a copy of the old file is kept) and reloads,
#   4. verifies: the right password works, a wrong or missing password is rejected.
# It never deletes or rewrites data. Docs: docs/PRIVACY.md, "Securing an existing database".
#
# Usage: scripts\secure-existing-db.ps1 [-Container <name-or-id>] [-BackupDir <dir>] [-SkipBackup]
# Default container: the compose service "db" of this repository (docker compose must be able to find it running).
param(
    [string]$Container = '',
    [string]$BackupDir = '',
    [switch]$SkipBackup
)
. (Join-Path $PSScriptRoot 'run-common.ps1')
Import-DotEnv
$user = 'ai-diary'; $db = 'ai-diary'

if (-not $env:JOURNAL_DB_PASSWORD -or $env:JOURNAL_DB_PASSWORD -eq 'change-me-to-a-long-random-value') {
    throw 'Set JOURNAL_DB_PASSWORD in .env to a new long random value first (it becomes the database password).'
}
if ($env:JOURNAL_DB_PASSWORD.Contains("'")) { throw "JOURNAL_DB_PASSWORD must not contain a single quote (')." }
if (-not $Container) {
    Push-Location $RepoRoot
    try { $Container = (docker compose ps -q db | Select-Object -First 1) } finally { Pop-Location }
    if (-not $Container) { throw 'The compose service "db" is not running. Start it (docker compose up -d db) or pass -Container <name>.' }
}
if (-not $BackupDir) { $BackupDir = Join-Path $RepoRoot 'backups' }

function Invoke-Psql {
    param([string]$Sql, [switch]$Tuples, [string]$Password = $env:JOURNAL_DB_PASSWORD, [switch]$Tcp)
    $a = @('exec', '-i', '-e', "PGPASSWORD=$Password", $Container, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', $user, '-d', $db)
    if ($Tcp) { $a += @('-h', '127.0.0.1') }
    if ($Tuples) { $a += @('-At') }
    $Sql | & docker @a
    if ($LASTEXITCODE -ne 0) { throw "psql failed ($LASTEXITCODE)" }
}

Write-Host "==> Container: $Container"
if (-not $SkipBackup) {
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    $file = Join-Path $BackupDir ("ai-diary-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.dump')
    Write-Host "==> Backup -> $file"
    docker exec -e "PGPASSWORD=$env:JOURNAL_DB_PASSWORD" $Container sh -c "pg_dump -U $user -Fc $db > /tmp/ai-diary-backup.dump"
    if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed; nothing was changed.' }
    docker cp "${Container}:/tmp/ai-diary-backup.dump" $file
    docker exec $Container rm -f /tmp/ai-diary-backup.dump | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $file) -or (Get-Item $file).Length -eq 0) { throw 'Backup failed; nothing was changed.' }
    Write-Host '    Keep this file private: it contains your journal.'
}

$hba = (Invoke-Psql -Tuples 'SHOW hba_file;').Trim()
Write-Host "==> Setting the password of role $user"
Invoke-Psql "ALTER ROLE `"$user`" WITH PASSWORD '$($env:JOURNAL_DB_PASSWORD)';"

Write-Host "==> Switching trust -> scram-sha-256 in $hba (backup copy: $hba.bak-before-secure)"
docker exec $Container sh -c "cp -n '$hba' '$hba.bak-before-secure' && sed -i -E '/^[[:space:]]*#/! s/\btrust\b/scram-sha-256/g' '$hba'"
if ($LASTEXITCODE -ne 0) { throw 'Editing pg_hba.conf failed.' }
Invoke-Psql 'SELECT pg_reload_conf();' | Out-Null

Write-Host '==> Verifying'
Invoke-Psql -Tcp -Tuples 'SELECT 1;' | Out-Null
Write-Host '    right password: OK'
$old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
'SELECT 1;' | & docker exec -i -e 'PGPASSWORD=definitely-wrong' $Container psql -U $user -d $db -h 127.0.0.1 -At 2>$null | Out-Null
$wrongRejected = ($LASTEXITCODE -ne 0)
$ErrorActionPreference = $old
if (-not $wrongRejected) { throw 'A wrong password was ACCEPTED: pg_hba.conf still allows trust. Check the file manually.' }
Write-Host '    wrong password rejected: OK'
Write-Host '==> Done. Restart the backend so it connects with the password (docker compose up -d backend).'
