param([switch]$Build)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Open Docker Desktop and wait for its engine to start.' }
$env:COMPOSE_PARALLEL_LIMIT = '2'
$composeArgs = @('compose', '-f', 'compose.local.json', 'up', '-d')
if ($Build) { $composeArgs += '--build' }
& docker @composeArgs
if ($LASTEXITCODE -ne 0) { throw 'Docker startup failed. Inspect docker compose -f compose.local.json logs.' }
if (-not (Test-Path -LiteralPath 'fe/node_modules/vite/bin/vite.js')) {
    npm.cmd --prefix fe install --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
}
$pidFile = Join-Path $PSScriptRoot '.frontend-local.pid'
$existing = $null
if (Test-Path -LiteralPath $pidFile) {
    $frontendPid = [int](Get-Content -LiteralPath $pidFile)
    $existing = Get-CimInstance Win32_Process -Filter "ProcessId=$frontendPid" -ErrorAction SilentlyContinue
}
$vite = Join-Path $PSScriptRoot 'fe/node_modules/vite/bin/vite.js'
if (-not $existing -or $existing.CommandLine -notlike "*$vite*") {
    $node = (Get-Command node.exe).Source
    $frontend = Start-Process -FilePath $node -ArgumentList @(('"' + $vite + '"'), '--host', '127.0.0.1', '--strictPort') -WorkingDirectory (Join-Path $PSScriptRoot 'fe') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $PSScriptRoot 'frontend-local.log') -RedirectStandardError (Join-Path $PSScriptRoot 'frontend-local.err.log') -PassThru
    $frontend.Id | Set-Content -LiteralPath $pidFile
}
Write-Output 'Frontend: http://localhost:3000 ; API: http://localhost:8080 ; Eureka: http://localhost:8761'
Write-Output 'Backend services can take 1-2 minutes to finish starting.'
