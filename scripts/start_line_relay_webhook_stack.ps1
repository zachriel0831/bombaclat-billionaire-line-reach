param(
  [string]$EnvFile = ".env",
  [string]$Port = "",
  [string]$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot",
  [string]$MavenRepo = "D:/work_space/.m2/repository",
  [string]$RedisServerExe = "",
  [string]$RedisConf = "",
  [string]$NgrokExe = "",
  [string]$NgrokUrl = "",
  [switch]$UpdateLineWebhook,
  [switch]$ForceRestart,
  [switch]$UseTaskScheduler,
  [switch]$NoRedis,
  [switch]$NoNgrok,
  [switch]$MavenRun
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $ProjectRoot "logs"
$RuntimeDir = Join-Path $ProjectRoot "runtime"
$RedisDir = Join-Path $RuntimeDir "redis"
$RuntimeLogDir = Join-Path $RuntimeDir "logs"

New-Item -ItemType Directory -Force -Path $LogDir, $RedisDir, $RuntimeLogDir | Out-Null

function Resolve-RepoPath([string]$PathValue) {
  if ([System.IO.Path]::IsPathRooted($PathValue)) {
    return $PathValue
  }
  return (Join-Path $ProjectRoot $PathValue)
}

function Load-EnvFile([string]$PathValue) {
  $resolved = Resolve-RepoPath $PathValue
  if (-not (Test-Path -LiteralPath $resolved)) {
    throw "Env file not found: $resolved"
  }

  Get-Content -LiteralPath $resolved | ForEach-Object {
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
}

function Get-EnvOrDefault([string]$Name, [string]$DefaultValue) {
  $value = [Environment]::GetEnvironmentVariable($Name, "Process")
  if ([string]::IsNullOrWhiteSpace($value)) {
    return $DefaultValue
  }
  return $value
}

function Resolve-CommandPath([string]$ExplicitPath, [string]$EnvName, [string[]]$CommandNames, [string[]]$FallbackPaths) {
  $candidates = @()
  if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
    $candidates += $ExplicitPath
  }
  $envPath = [Environment]::GetEnvironmentVariable($EnvName, "Process")
  if (-not [string]::IsNullOrWhiteSpace($envPath)) {
    $candidates += $envPath
  }
  foreach ($name in $CommandNames) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) {
      $candidates += $cmd.Source
    }
  }
  $candidates += $FallbackPaths

  foreach ($candidate in $candidates) {
    if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
      return (Resolve-Path -LiteralPath $candidate).Path
    }
  }
  return ""
}

function Test-TcpPort([int]$LocalPort) {
  return (Get-PortOwnerPids $LocalPort).Count -gt 0
}

function Get-PortOwnerPids([int]$LocalPort) {
  return @(Get-NetTCPConnection -State Listen -LocalPort $LocalPort -ErrorAction SilentlyContinue |
    Where-Object { $_.OwningProcess -gt 0 } |
    Select-Object -ExpandProperty OwningProcess -Unique)
}

function Wait-PortReleased([int]$LocalPort, [int]$Seconds, [string]$Label) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  do {
    $currentPids = Get-PortOwnerPids $LocalPort
    if ($currentPids.Count -eq 0) {
      Write-Host "[$Label] port $LocalPort released"
      return
    }
    Start-Sleep -Seconds 1
  } while ((Get-Date) -lt $deadline)

  $remainingPids = Get-PortOwnerPids $LocalPort
  throw "Timed out waiting for $Label port $LocalPort to release; still owned by PID(s): $($remainingPids -join ', ')"
}

function Stop-PortOwner([int]$LocalPort, [string]$Label) {
  $pids = Get-PortOwnerPids $LocalPort
  if ($pids.Count -eq 0) {
    Write-Host "[$Label] no listener on port $LocalPort"
    return
  }

  foreach ($ownerPid in $pids) {
    Write-Host "[$Label] stopping PID $ownerPid on port $LocalPort"
    try {
      Stop-Process -Id $ownerPid -Force -ErrorAction Stop
    } catch {
      throw "Failed to stop $Label PID $ownerPid on port ${LocalPort}: $($_.Exception.Message)"
    }
  }

  Wait-PortReleased $LocalPort 15 $Label
}

function Convert-ToCmdSetLine([string]$Name, [string]$Value) {
  if ([string]::IsNullOrWhiteSpace($Value)) {
    return ""
  }
  $escaped = $Value.Replace("^", "^^").Replace("&", "^&").Replace("|", "^|").Replace("<", "^<").Replace(">", "^>")
  return "set `"$Name=$escaped`""
}

function Get-CmdEnvironmentPrelude {
  $lines = @()
  $javaHomeLine = Convert-ToCmdSetLine "JAVA_HOME" $env:JAVA_HOME
  if ($javaHomeLine -ne "") {
    $lines += $javaHomeLine
    $lines += 'set "PATH=%JAVA_HOME%\bin;%PATH%"'
  }
  return ($lines -join "`r`n")
}

function Get-TaskRunTime {
  $runAt = (Get-Date).AddMinutes(1)
  return $runAt.ToString("HH:mm")
}

function Invoke-ScheduledTaskOnce([string]$TaskName, [string]$CmdPath) {
  schtasks.exe /Create /TN "\$TaskName" /SC ONCE /ST (Get-TaskRunTime) /TR $CmdPath /F | Out-Null
  try {
    schtasks.exe /Run /TN "\$TaskName" | Out-Null
    Start-Sleep -Seconds 2
  } finally {
    schtasks.exe /Delete /TN "\$TaskName" /F | Out-Null
  }
}

function Write-Launcher([string]$Name, [string]$CommandLine, [string]$WorkingDirectory, [string]$OutLog, [string]$ErrLog) {
  $cmdPath = Join-Path $LogDir "$Name.cmd"
  $envPrelude = Get-CmdEnvironmentPrelude
  $cmd = @"
@echo off
$envPrelude
cd /d "$WorkingDirectory"
$CommandLine >> "$OutLog" 2>> "$ErrLog"
"@
  Set-Content -LiteralPath $cmdPath -Value $cmd -Encoding ASCII
  return $cmdPath
}

function Start-DetachedCommand([string]$Name, [string]$CommandLine, [string]$WorkingDirectory, [string]$OutLog, [string]$ErrLog) {
  $cmdPath = Write-Launcher $Name $CommandLine $WorkingDirectory $OutLog $ErrLog

  if ($UseTaskScheduler) {
    Invoke-ScheduledTaskOnce "LineRelay-$Name" $cmdPath
    return
  }

  Start-Process -FilePath $cmdPath -WorkingDirectory $WorkingDirectory -WindowStyle Hidden | Out-Null
}

function Wait-HttpOk([string]$Url, [int]$Seconds, [string]$Label) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  do {
    try {
      $response = Invoke-RestMethod -Uri $Url -TimeoutSec 5
      Write-Host "[$Label] ready: $Url"
      return $response
    } catch {
      Start-Sleep -Seconds 1
    }
  } while ((Get-Date) -lt $deadline)

  throw "Timed out waiting for $Label at $Url"
}

function Wait-Redis([string]$RedisCli, [int]$RedisPort, [int]$Seconds) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  do {
    if (-not [string]::IsNullOrWhiteSpace($RedisCli)) {
      $pong = ""
      try {
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $pong = & $RedisCli -h 127.0.0.1 -p $RedisPort ping 2>$null
      } catch {
        $pong = ""
      } finally {
        $ErrorActionPreference = $previousErrorAction
      }
      if ($pong -eq "PONG") {
        Write-Host "[redis] ready: PONG"
        return
      }
    } elseif (Test-TcpPort $RedisPort) {
      Write-Host "[redis] port $RedisPort is listening"
      return
    }
    Start-Sleep -Seconds 1
  } while ((Get-Date) -lt $deadline)

  throw "Timed out waiting for Redis on port $RedisPort"
}

Load-EnvFile $EnvFile

if ($Port -ne "") {
  $env:PORT = $Port
}
$servicePort = [int](Get-EnvOrDefault "PORT" "8080")
$redisPort = [int](Get-EnvOrDefault "SPRING_DATA_REDIS_PORT" "6379")

if ([string]::IsNullOrWhiteSpace($NgrokUrl)) {
  $NgrokUrl = Get-EnvOrDefault "LINE_RELAY_NGROK_URL" "https://7823-220-141-219-53.ngrok-free.app"
}

if ($JavaHome -ne "" -and (Test-Path -LiteralPath $JavaHome)) {
  $env:JAVA_HOME = $JavaHome
  $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

$redisExe = Resolve-CommandPath $RedisServerExe "LINE_RELAY_REDIS_SERVER_EXE" @("redis-server") @("D:\work_space\tools\redis-msarchive\extract\Redis\redis-server.exe")
$redisCli = Resolve-CommandPath "" "LINE_RELAY_REDIS_CLI_EXE" @("redis-cli") @("D:\work_space\tools\redis-msarchive\extract\Redis\redis-cli.exe")
if ([string]::IsNullOrWhiteSpace($RedisConf) -and -not [string]::IsNullOrWhiteSpace($redisExe)) {
  $envRedisConf = [Environment]::GetEnvironmentVariable("LINE_RELAY_REDIS_CONF", "Process")
  if (-not [string]::IsNullOrWhiteSpace($envRedisConf)) {
    $RedisConf = $envRedisConf
  } else {
    $RedisConf = Join-Path (Split-Path -Parent $redisExe) "redis.windows.conf"
  }
}

$ngrok = Resolve-CommandPath $NgrokExe "LINE_RELAY_NGROK_EXE" @("ngrok") @("C:\Users\Zack Ou\Downloads\ngrok.exe")

Set-Location $ProjectRoot

if (-not $NoRedis) {
  if ($ForceRestart -or -not (Test-TcpPort $redisPort)) {
    if ([string]::IsNullOrWhiteSpace($redisExe)) {
      throw "redis-server not found. Set LINE_RELAY_REDIS_SERVER_EXE in .env or install redis-server in PATH."
    }
    if ($ForceRestart) {
      Stop-PortOwner $redisPort "redis"
      Start-Sleep -Seconds 1
    }
    $redisLog = Join-Path $RuntimeLogDir "redis.log"
    $redisOutLog = Join-Path $RuntimeLogDir "redis.stdout.log"
    $redisErrLog = Join-Path $RuntimeLogDir "redis.err.log"
    $redisCmd = "`"$redisExe`""
    if (-not [string]::IsNullOrWhiteSpace($RedisConf) -and (Test-Path -LiteralPath $RedisConf)) {
      $redisCmd += " `"$RedisConf`""
    }
    $redisCmd += " --port $redisPort --dir `"$RedisDir`" --logfile `"$redisLog`""
    Write-Host "[redis] starting on port $redisPort"
    Start-DetachedCommand "redis" $redisCmd $RedisDir $redisOutLog $redisErrLog
  } else {
    Write-Host "[redis] already listening on port $redisPort"
  }
  Wait-Redis $redisCli $redisPort 20
}

if ($ForceRestart) {
  Stop-PortOwner $servicePort "line-relay"
  Start-Sleep -Seconds 1
}

$healthUrl = "http://127.0.0.1:$servicePort/health"
$serviceReady = $false
try {
  $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3
  if ($health.service -eq "line-relay-service" -and $health.status -eq "ok") {
    $serviceReady = $true
    Write-Host "[line-relay] already healthy on port $servicePort"
  }
} catch {
  $serviceReady = $false
}

if (-not $serviceReady) {
  $jarPath = Join-Path $ProjectRoot "target\line-relay-service-0.1.0-SNAPSHOT.jar"
  $latestInput = Get-ChildItem -Path (Join-Path $ProjectRoot "pom.xml"), (Join-Path $ProjectRoot "src") -Recurse -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

  $serviceOut = Join-Path $LogDir "line-relay-service.stack.out.log"
  $serviceErr = Join-Path $LogDir "line-relay-service.stack.err.log"
  if ((-not $MavenRun) -and (Test-Path -LiteralPath $jarPath) -and ((Get-Item $jarPath).LastWriteTime -ge $latestInput.LastWriteTime)) {
    $serviceCmd = "`"java`" -jar `"$jarPath`""
    Write-Host "[line-relay] starting packaged jar on port $servicePort"
  } else {
    $serviceCmd = "`"$ProjectRoot\mvnw.cmd`" `"-Dmaven.repo.local=$MavenRepo`" spring-boot:run"
    Write-Host "[line-relay] starting Maven spring-boot:run on port $servicePort"
  }
  Start-DetachedCommand "line-relay" $serviceCmd $ProjectRoot $serviceOut $serviceErr
  Wait-HttpOk $healthUrl 45 "line-relay" | Out-Null
}

if (-not $NoNgrok) {
  if ([string]::IsNullOrWhiteSpace($ngrok)) {
    throw "ngrok not found. Set LINE_RELAY_NGROK_EXE in .env or install ngrok in PATH."
  }

  $desiredAddr = "http://localhost:$servicePort"
  $ngrokOk = $false
  try {
    $tunnels = Invoke-RestMethod -Uri "http://127.0.0.1:4040/api/tunnels" -TimeoutSec 3
    foreach ($tunnel in $tunnels.tunnels) {
      if ($tunnel.public_url -eq $NgrokUrl -and $tunnel.config.addr -eq $desiredAddr) {
        $ngrokOk = $true
      }
    }
  } catch {
    $ngrokOk = $false
  }

  if (-not $ngrokOk) {
    Write-Host "[ngrok] restarting tunnel: $NgrokUrl -> $desiredAddr"
    Get-Process -Name ngrok -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
    $ngrokOut = Join-Path $LogDir "ngrok-line-relay.log"
    $ngrokErr = Join-Path $LogDir "ngrok-line-relay.err.log"
    $ngrokCmd = "`"$ngrok`" http $servicePort --url $NgrokUrl --log stdout --log-format logfmt --log-level info"
    Start-DetachedCommand "ngrok-line-relay" $ngrokCmd $ProjectRoot $ngrokOut $ngrokErr
  } else {
    Write-Host "[ngrok] already correct: $NgrokUrl -> $desiredAddr"
  }

  $deadline = (Get-Date).AddSeconds(25)
  do {
    try {
      $tunnels = Invoke-RestMethod -Uri "http://127.0.0.1:4040/api/tunnels" -TimeoutSec 3
      foreach ($tunnel in $tunnels.tunnels) {
        if ($tunnel.public_url -eq $NgrokUrl -and $tunnel.config.addr -eq $desiredAddr) {
          Write-Host "[ngrok] ready: $NgrokUrl -> $desiredAddr"
          $ngrokOk = $true
          break
        }
      }
      if ($ngrokOk) {
        break
      }
    } catch {
      Start-Sleep -Seconds 1
    }
  } while ((Get-Date) -lt $deadline)

  if (-not $ngrokOk) {
    throw "Timed out waiting for ngrok tunnel $NgrokUrl -> $desiredAddr"
  }

  Wait-HttpOk "$NgrokUrl/health" 20 "ngrok health" | Out-Null

  if ($UpdateLineWebhook) {
    $token = Get-EnvOrDefault "LINE_CHANNEL_ACCESS_TOKEN" ""
    if ([string]::IsNullOrWhiteSpace($token)) {
      throw "LINE_CHANNEL_ACCESS_TOKEN is required for -UpdateLineWebhook"
    }
    $endpoint = "$NgrokUrl/webhook"
    $headers = @{ Authorization = "Bearer $token" }
    $body = @{ endpoint = $endpoint } | ConvertTo-Json
    Invoke-RestMethod -Method Put -Uri "https://api.line.me/v2/bot/channel/webhook/endpoint" -Headers $headers -ContentType "application/json" -Body $body | Out-Null
    $testBody = @{ endpoint = $endpoint } | ConvertTo-Json
    $test = Invoke-RestMethod -Method Post -Uri "https://api.line.me/v2/bot/channel/webhook/test" -Headers $headers -ContentType "application/json" -Body $testBody
    Write-Host "[line] webhook endpoint: $endpoint"
    Write-Host "[line] webhook test: success=$($test.success) statusCode=$($test.statusCode) detail=$($test.detail)"
  }
}

Write-Host "[done] line-relay health: $healthUrl"
if (-not $NoNgrok) {
  Write-Host "[done] public webhook: $NgrokUrl/webhook"
}
