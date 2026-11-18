# Chay unit test INV / INS / BOT bang Docker - KHONG can cai Java hay Python tren may.
# Cach chay (PowerShell, tai thu muc goc cua project):
#     .\scripts\run-unit-tests.ps1

$root = (Resolve-Path "$PSScriptRoot\..").Path
docker volume create gradle-cache | Out-Null

Write-Host ""
Write-Host "=== [1/3] INV + INS (Gradle, JDK 17) - lan dau mat vai phut de tai thu vien ===" -ForegroundColor Cyan
$gradleCmd = "cd /workspace/service/common && gradle publishToMavenLocal -x test --no-daemon -q --console=plain ; " +
             "cd /workspace/service/invoice-service && gradle cleanTest test --tests '*InvoiceServiceTest' --no-daemon --console=plain ; " +
             "cd /workspace/service/insurance-service && gradle cleanTest test --tests '*InsuranceClaimServiceTest' --no-daemon --console=plain"
docker run --rm -v "${root}:/workspace" -v gradle-cache:/home/gradle/.gradle -w /workspace gradle:8.5-jdk17 sh -c $gradleCmd

Write-Host ""
Write-Host "=== [2/3] BOT (pytest, Python 3.13) ===" -ForegroundColor Cyan
$pyCmd = "pip install -q --root-user-action=ignore -r requirements-test.txt && python -m pytest -v --junitxml=test-results.xml"
docker run --rm -v "${root}:/workspace" -w /workspace/service/chatbot-service python:3.13-slim sh -c $pyCmd

Write-Host ""
Write-Host "=== [3/3] Tong hop ket qua ===" -ForegroundColor Cyan
docker run --rm -v "${root}:/workspace" -w /workspace python:3.13-slim python scripts/summarize_unit_tests.py
