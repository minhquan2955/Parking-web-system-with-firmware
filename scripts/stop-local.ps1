$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$file = Join-Path $projectRoot '.runtime/processes.json'
if (!(Test-Path -LiteralPath $file)) { exit 0 }
$processIds = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
$dataDirectory = [IO.Path]::GetFullPath((Join-Path $projectRoot '.runtime/postgres'))
$normalizedDataDirectory = $dataDirectory.Replace('\','/')
$postgresProcess = Get-CimInstance Win32_Process -Filter "Name='postgres.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -and $_.CommandLine.Replace('\','/').IndexOf($normalizedDataDirectory, [StringComparison]::OrdinalIgnoreCase) -ge 0 } | Select-Object -First 1
foreach ($processId in @($processIds.backend, $processIds.frontend)) {
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
    if ($processInfo -and ($processInfo.CommandLine -like '*vn.parking.DevLauncher*' -or $processInfo.CommandLine -like '*node_modules/next/dist/bin/next*' -or $processInfo.CommandLine -like '*.next/standalone/server.js*')) { Stop-Process -Id $processId }
}
if ($postgresProcess) {
    $pgControl = Join-Path (Split-Path -Parent $postgresProcess.ExecutablePath) 'pg_ctl.exe'
    & $pgControl -D $dataDirectory -m fast stop
}
Write-Host 'Stopped project backend/frontend. Persistent database files are preserved.'
