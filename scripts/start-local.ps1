param([switch]$SkipBuild, [switch]$Lan)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (!(Test-Path -LiteralPath '.env')) { & "$PSScriptRoot/init-env.ps1" }
foreach ($line in [IO.File]::ReadAllLines((Join-Path $projectRoot '.env'))) {
    if ($line -match '^([A-Z_][A-Z0-9_]*)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
}
New-Item -ItemType Directory -Force -Path '.runtime' | Out-Null
$maven = Join-Path $projectRoot 'backend/mvnw.cmd'
if (Test-Path -LiteralPath '.tools/apache-maven-3.9.11/bin/mvn.cmd') { $maven = Join-Path $projectRoot '.tools/apache-maven-3.9.11/bin/mvn.cmd' }
if (!$SkipBuild) {
    & $maven -f backend/pom.xml "-Dmaven.repo.local=$projectRoot/.tools/m2" test-compile dependency:build-classpath '-Dmdep.includeScope=test' "-Dmdep.outputFile=$projectRoot/.runtime/classpath.txt" -q
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' }
    Push-Location frontend
    npm ci --no-fund
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Frontend dependency installation failed' }
    npm run build
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Frontend build failed' }
    Pop-Location
}
if (Get-NetTCPConnection -State Listen -LocalPort 8080,3000,5440 -ErrorAction SilentlyContinue) { throw 'A local port (8080,3000,5440) is occupied. Stop the previous project instance first.' }
$classpath = "$projectRoot/backend/target/test-classes;$projectRoot/backend/target/classes;" + [IO.File]::ReadAllText((Join-Path $projectRoot '.runtime/classpath.txt')).Trim()
$javaArgs = @('-cp', "`"$classpath`"", "`"-Dparking.local-data=$projectRoot/.runtime/postgres`"", 'vn.parking.DevLauncher')
$backend = Start-Process -FilePath 'java' -ArgumentList $javaArgs -WorkingDirectory $projectRoot -PassThru -WindowStyle Hidden -RedirectStandardOutput '.runtime/backend.log' -RedirectStandardError '.runtime/backend-error.log'
$env:HOSTNAME = if ($Lan) { '0.0.0.0' } else { '127.0.0.1' }
$env:PORT = '3000'
Push-Location frontend
node scripts/prepare-standalone.mjs
Pop-Location
$frontend = Start-Process -FilePath 'node' -ArgumentList @('.next/standalone/server.js') -WorkingDirectory (Join-Path $projectRoot 'frontend') -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot '.runtime/frontend.log') -RedirectStandardError (Join-Path $projectRoot '.runtime/frontend-error.log')
@{backend=$backend.Id;frontend=$frontend.Id} | ConvertTo-Json | Set-Content -LiteralPath '.runtime/processes.json'
$ready = $false
for ($attempt = 0; $attempt -lt 45; $attempt++) {
    try {
        $null = Invoke-RestMethod -Uri 'http://127.0.0.1:3000/api/v1/auth/csrf' -TimeoutSec 2
        $ready = $true
        break
    } catch { Start-Sleep -Seconds 1 }
}
if (!$ready) { throw 'Startup did not become ready. Inspect .runtime/backend-error.log and .runtime/frontend-error.log.' }
Write-Host 'Ready: http://localhost:3000 . Username: admin. Password: ADMIN_PASSWORD in .env.'
Write-Host 'Startup logs: .runtime/backend.log and .runtime/frontend.log.'
if ($Lan) {
    Write-Host 'LAN mode: web/API listen on port 3000. ESP32 uses http://<computer-LAN-IPv4>:3000; backend remains on loopback.'
} else {
    Write-Host 'Web/backend bind to loopback. Use -Lan to connect the ESP32 on the same network.'
}
