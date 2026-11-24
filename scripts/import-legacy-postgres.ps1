# One-time import of the OLD PostgreSQL version's data (tables documents + chat_history) into the embedded H2 file
# database. Works on a COPY of the old data directory with a temporary postgres container; the original stays untouched.
# Run it while the backend is stopped, before the first start of this version.
#   pwsh scripts/import-legacy-postgres.ps1 -DataDir db/data_personal [-H2Url jdbc:h2:file:./data/journal]
# Needs Docker (only for this import) and a Maven-cached H2 jar (run .\mvnw.cmd package once).
param(
    [Parameter(Mandatory)][string]$DataDir,
    [string]$H2Url = 'jdbc:h2:file:./data/journal;DB_CLOSE_ON_EXIT=FALSE',
    [string]$Image = 'postgres:17'
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$src = Resolve-Path $DataDir
$pgdata = if (Test-Path (Join-Path $src 'pgdata')) { '/var/lib/postgresql/data/pgdata' } else { '/var/lib/postgresql/data' }
$h2 = Get-ChildItem (Join-Path $HOME '.m2/repository/com/h2database/h2') -Recurse -Filter 'h2-*.jar' |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object FullName | Select-Object -Last 1
if (-not $h2) { throw 'H2 jar not found in ~/.m2: run .\mvnw.cmd package once.' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }

$work = Join-Path ([IO.Path]::GetTempPath()) ("diary-import-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $work | Out-Null
$copy = Join-Path $work 'pg'
Copy-Item $src $copy -Recurse
$name = 'diary-import-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$env:MSYS_NO_PATHCONV = '1'
try {
    docker run -d --name $name -e POSTGRES_PASSWORD=import -e "PGDATA=$pgdata" -v "${copy}:/var/lib/postgresql/data" $Image | Out-Null
    $ready = $false
    for ($i = 0; $i -lt 40 -and -not $ready; $i++) {
        Start-Sleep 2
        docker exec $name pg_isready -U ai-diary 2>$null | Out-Null
        $ready = ($LASTEXITCODE -eq 0)
    }
    if (-not $ready) { throw 'temporary postgres did not start (is the data directory from PostgreSQL 17?)' }
    # the old documents table has a pgvector column this plain image cannot read: copy the plain columns first
    docker exec $name psql -U ai-diary -d ai-diary -v ON_ERROR_STOP=1 -c 'CREATE TABLE imp_documents AS SELECT id, aicontent, entrytext, "timestamp" AS "TIMESTAMP" FROM documents' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'reading documents failed' }
    docker exec $name pg_dump -U ai-diary -d ai-diary --data-only --column-inserts --no-owner -t imp_documents -t chat_history -f /tmp/legacy.sql
    if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed' }
    docker cp "${name}:/tmp/legacy.sql" (Join-Path $work 'legacy.sql')
} finally {
    docker rm -f $name 2>$null | Out-Null
}

# keep only the INSERT statements (multi-line string values continue on following lines)
$out = New-Object System.Text.StringBuilder
$in = $false
foreach ($line in [IO.File]::ReadAllLines((Join-Path $work 'legacy.sql'), [Text.Encoding]::UTF8)) {
    if (-not $in -and $line.StartsWith('INSERT INTO ')) { $in = $true; $line = $line.Replace('INSERT INTO public.imp_documents', 'INSERT INTO documents').Replace('INSERT INTO public.', 'INSERT INTO ') }
    if ($in) { [void]$out.AppendLine($line); if ($line.EndsWith(');')) { $in = $false } }
}
$baseline = Join-Path $root 'backend/src/main/resources/db/migration/V1__baseline.sql'
$script = Join-Path $work 'import.sql'
$text = [IO.File]::ReadAllText($baseline) + "`n" + $out.ToString() +
    "`nALTER SEQUENCE documents_seq RESTART WITH 10000;`nALTER SEQUENCE chat_history_seq RESTART WITH 10000;`n"
[IO.File]::WriteAllText($script, $text, (New-Object Text.UTF8Encoding($false)))

& $java -cp $h2.FullName org.h2.tools.RunScript -url $H2Url -user journal -script $script
if ($LASTEXITCODE -ne 0) { throw 'loading into H2 failed (is the backend still running?)' }
Remove-Item $work -Recurse -Force
Write-Host 'Imported legacy documents and chat_history. Start the backend: it upgrades the old tables to entries on first start.'
