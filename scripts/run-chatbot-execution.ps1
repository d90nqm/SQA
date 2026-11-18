# ============================================================================
# BOT sheet - chay TUNG test case rieng, hien thi ro rang (RUNNING / Workdir / Command / RESULT)
#
# Cach chay (PowerShell, tai thu muc goc project SQA4):
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-chatbot-execution.ps1 -Target ALL
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-chatbot-execution.ps1 -Target BOT-04
#
# Yeu cau: Python + pip install -r service\chatbot-service\requirements-test.txt
# ============================================================================
param(
    [string]$Target = "ALL",
    [string]$Python = "python"
)

$ErrorActionPreference = "Continue"
$root    = (Resolve-Path "$PSScriptRoot\..").Path
$svcName = "chatbot-service"
$workdir = Join-Path $root "service\chatbot-service"

# ID ; pytest node id
$cases = @(
    @{ Id = "BOT-01"; Node = "tests/services/test_ai_engine.py::test_bot_01_predict_intent_should_use_text_preprocessing_in_heuristic_runtime_path"}
    @{ Id = "BOT-02"; Node = "tests/services/test_ai_engine.py::test_bot_02_predict_ner_should_apply_text_preprocessing_in_heuristic_runtime_path"}
    @{ Id = "BOT-03"; Node = "tests/repositories/test_neo4j_repo.py::test_bot_03_search_with_relations_should_return_text_and_formatted_neighbors"}
    @{ Id = "BOT-04"; Node = "tests/repositories/test_neo4j_repo.py::test_bot_04_search_with_relations_should_return_empty_list_when_vector_store_missing_or_search_fails"}
    @{ Id = "BOT-05"; Node = "tests/services/test_chat_service.py::test_bot_05_process_question_should_execute_thin_runtime_orchestration"}
    @{ Id = "BOT-06"; Node = "tests/services/test_ai_engine.py::test_bot_06_predict_intent_should_return_default_for_unknown_question"}
    @{ Id = "BOT-07"; Node = "tests/services/test_chat_service.py::test_bot_07_generate_response_should_use_fallback_when_llm_is_disabled"}
)

function Write-Line([string]$ch, [string]$color) {
    Write-Host ($ch * 78) -ForegroundColor $color
}

$selected = $cases | Where-Object { $Target -eq "ALL" -or $_.Id -eq $Target }
if (-not $selected) {
    Write-Host "Target '$Target' khong hop le. Dung ALL hoac BOT-01 .. BOT-07" -ForegroundColor Red
    exit 2
}

$summary = @()
foreach ($c in $selected) {
    Write-Host ""
    Write-Line "=" "Cyan"
    Write-Host "RUNNING :: $svcName execution :: $($c.Id)" -ForegroundColor Cyan
    Write-Line "=" "Cyan"
    Write-Host ""
    Write-Host "Workdir : $workdir"
    Write-Host "Command : $Python -m pytest $($c.Node) -q"

    Push-Location $workdir
    & $Python -m pytest $c.Node -q
    $code = $LASTEXITCODE
    Pop-Location

    $status = if ($code -eq 0) { "PASS" } else { "FAIL" }
    $color  = if ($status -eq "PASS") { "Green" } else { "Red" }
    Write-Host ""
    Write-Line "#" $color
    Write-Host "RESULT: $status" -ForegroundColor $color
    Write-Host "TARGET: $svcName execution :: $($c.Id)" -ForegroundColor $color
    Write-Host "CODE  : $code" -ForegroundColor $color
    Write-Line "#" $color
    Write-Host ""

    $summary += [pscustomobject]@{ ID = $c.Id; Result = $status }
}

Write-Host ""
Write-Line "=" "Cyan"
Write-Host "SUMMARY :: $svcName ($($summary.Count) test case)" -ForegroundColor Cyan
Write-Line "=" "Cyan"
$summary | Format-Table -AutoSize
$p = @($summary | Where-Object Result -eq "PASS").Count
Write-Host "Pass: $p   Fail: $($summary.Count - $p)"
