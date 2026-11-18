$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$config = & docker compose config --format json
if ($LASTEXITCODE -ne 0) { throw 'Unable to read the original Docker Compose configuration.' }
$config | Set-Content -Encoding utf8 -LiteralPath 'compose.local.json'
python scripts/prepare-local.py
if ($LASTEXITCODE -ne 0) { throw 'Unable to generate the local configuration.' }
