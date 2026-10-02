$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envFile) { Write-Host '.env already exists; preserved.'; exit 0 }
function New-Secret {
    $buffer = New-Object byte[] 30
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($buffer)
    $rng.Dispose()
    return [Convert]::ToBase64String($buffer)
}
$text = [IO.File]::ReadAllText((Join-Path $projectRoot '.env.example'))
$text = $text.Replace('replace-with-a-long-random-password', (New-Secret))
$text = $text.Replace('replace-with-a-unique-10-to-72-byte-password', (New-Secret))
$text = $text.Replace('replace-with-a-random-device-key-at-least-24-characters', (New-Secret))
[IO.File]::WriteAllText($envFile, $text, (New-Object Text.UTF8Encoding $false))
Write-Host 'Created .env with unique random credentials. Read ADMIN_PASSWORD locally; do not commit or share .env.'
