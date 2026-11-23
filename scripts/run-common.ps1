# Shared helpers for scripts/run-*.ps1 (dot-source this file). Not meant to be run directly.
$ErrorActionPreference = 'Stop'
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Import-DotEnv {
    # Loads KEY=VALUE lines from .env into the process environment. Variables that are already set win.
    param([string]$Path = (Join-Path $script:RepoRoot '.env'))
    if (-not (Test-Path $Path)) { return }
    foreach ($line in Get-Content $Path) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $k = $t.Substring(0, $i).Trim()
        $v = $t.Substring($i + 1).Trim().Trim('"').Trim("'")
        if (-not [Environment]::GetEnvironmentVariable($k, 'Process')) {
            [Environment]::SetEnvironmentVariable($k, $v, 'Process')
        }
    }
}

function Set-DefaultEnv {
    param([string]$Name, [string]$Value)
    if (-not [Environment]::GetEnvironmentVariable($Name, 'Process')) {
        [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
    }
}

function Get-JavaExe {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        return (Join-Path $env:JAVA_HOME 'bin/java.exe')
    }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $cmd) { throw 'Java 25 not found. Install a JDK 25 (Temurin) and set JAVA_HOME.' }
    return $cmd.Source
}

function Invoke-Maven {
    param([string[]]$MavenArgs)
    & (Join-Path $script:RepoRoot 'mvnw.cmd') -B -ntp @MavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven failed ($LASTEXITCODE)" }
}

function Assert-File {
    param([string]$Path, [string]$Hint)
    if (-not (Test-Path $Path)) { throw "Missing $Path. $Hint" }
}
