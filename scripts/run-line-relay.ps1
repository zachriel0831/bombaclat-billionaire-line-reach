$Host.UI.RawUI.WindowTitle = 'line-relay-service 8080'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location 'd:\work_space\claude-box\workspace\line-relay-service'
if (-not (Test-Path 'logs')) { New-Item -ItemType Directory -Path 'logs' | Out-Null }
Write-Host "[run-line-relay] starting mvnw spring-boot:run, log: logs\line-relay.log"
& .\mvnw.cmd spring-boot:run 2>&1 | Tee-Object -FilePath logs\line-relay.log
Write-Host "[run-line-relay] mvnw exited with code $LASTEXITCODE"
