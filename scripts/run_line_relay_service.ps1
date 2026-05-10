param(
  # Repo-local .env is loaded into the current process before Spring Boot starts.
  [string]$EnvFile = ".env",
  # Optional port override for running next to another local service.
  [string]$Port = "",
  # Default JDK path used on this Windows workstation.
  [string]$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ResolvedEnvFile = if ([System.IO.Path]::IsPathRooted($EnvFile)) {
  $EnvFile
} else {
  Join-Path $ProjectRoot $EnvFile
}

if (-not (Test-Path -LiteralPath $ResolvedEnvFile)) {
  throw "Env file not found: $ResolvedEnvFile"
}

# Load simple KEY=VALUE lines. This intentionally ignores blank/comment/malformed
# lines and does not print values because the file contains LINE secrets.
Get-Content -LiteralPath $ResolvedEnvFile | ForEach-Object {
  $line = $_.Trim()
  if ($line.Length -eq 0 -or $line.StartsWith("#")) {
    return
  }
  $parts = $line.Split("=", 2)
  if ($parts.Count -ne 2 -or [string]::IsNullOrWhiteSpace($parts[0])) {
    return
  }
  [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), "Process")
}

if ($Port -ne "") {
  $env:PORT = $Port
}

# Prefer a known JDK 21 install if present; otherwise rely on the caller's PATH.
if ($JavaHome -ne "" -and (Test-Path -LiteralPath $JavaHome)) {
  $env:JAVA_HOME = $JavaHome
  $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

Set-Location $ProjectRoot
# This script is a convenience wrapper. Direct `java -jar` or IDE application
# startup also works because application.yml imports `.env`.
.\mvnw.cmd spring-boot:run
