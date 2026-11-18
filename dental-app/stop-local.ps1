$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$pidFile = Join-Path $PSScriptRoot '.frontend-local.pid'
if (Test-Path -LiteralPath $pidFile) {
    $frontendPid = [int](Get-Content -LiteralPath $pidFile)
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$frontendPid" -ErrorAction SilentlyContinue
    $vite = Join-Path $PSScriptRoot 'fe/node_modules/vite/bin/vite.js'
    if ($process -and $process.CommandLine -like "*$vite*") {
        Stop-Process -Id $frontendPid
        Remove-Item -LiteralPath $pidFile
    }
}
docker compose -f compose.local.json stop
