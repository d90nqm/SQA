# ============================================================================
# INS sheet - chay TUNG test case rieng, hien thi ro rang (RUNNING / Workdir / Command / RESULT)
#
# Cach chay (PowerShell, tai thu muc goc project SQA4):
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-insurance-execution.ps1 -Target ALL
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-insurance-execution.ps1 -Target INS-05
#
# Yeu cau: JDK 17 (gradlew tu tai Gradle). Lan dau se publish module common vao mavenLocal.
# ============================================================================
param(
    [string]$Target = "ALL"
)

$ErrorActionPreference = "Continue"
$root    = (Resolve-Path "$PSScriptRoot\..").Path
$svcName = "insurance-service"
$workdir = Join-Path $root "service\insurance-service"
$class   = "com.main_project.insurance_service.service.InsuranceClaimServiceTest"
$xmlFile = Join-Path $workdir "build\test-results\test\TEST-com.main_project.insurance_service.service.InsuranceClaimServiceTest.xml"
$gradle  = Join-Path $workdir "gradlew.bat"

# ID ; method name
$cases = @(
    @{ Id = "INS-01"; Method = "ins01_processInvoiceClaim_shouldCreatePendingClaimAndReturnCorrectPaymentSplit"}
    @{ Id = "INS-02"; Method = "ins02_processInvoiceClaim_shouldThrowWhenInsuranceExpiredOrInactive"}
    @{ Id = "INS-03"; Method = "ins03_processInvoiceItemsForValidation_shouldReturnPerItemSplitWithCoverageCapAndUncoveredCase"}
    @{ Id = "INS-04"; Method = "ins04_createClaimFromValidationEvent_shouldAggregateItemTotalsAndPersistClaim"}
    @{ Id = "INS-05"; Method = "ins05_createClaimItemRequestFromInvoiceItem_shouldCalculateInsurancePayRatio"}
    @{ Id = "INS-06"; Method = "ins06_createClaimItemRequestFromInvoiceItem_shouldHandleZeroAmountWithoutDivideByZero"}
    @{ Id = "INS-07"; Method = "ins07_processInvoiceItemsForValidation_shouldRejectPatientInsuranceWithoutPolicy"}
)

function Write-Line([string]$ch, [string]$color) {
    Write-Host ($ch * 78) -ForegroundColor $color
}

# Publish module common (dependency cua service) neu chua co trong mavenLocal
$commonRepo = Join-Path $HOME ".m2\repository\com\do_an\common\1.0.0"
if (-not (Test-Path $commonRepo)) {
    Write-Host "Publishing service\common to mavenLocal (lan dau) ..." -ForegroundColor Yellow
    & $gradle -p (Join-Path $root "service\common") publishToMavenLocal -x test --console=plain -q
}

$selected = $cases | Where-Object { $Target -eq "ALL" -or $_.Id -eq $Target }
if (-not $selected) {
    Write-Host "Target '$Target' khong hop le. Dung ALL hoac INS-01 .. INS-07" -ForegroundColor Red
    exit 2
}

$summary = @()
foreach ($c in $selected) {
    $title  = "RUNNING :: $svcName execution :: $($c.Id)"
    $filter = "$class.$($c.Method)"
    Write-Host ""
    Write-Line "=" "Cyan"
    Write-Host $title -ForegroundColor Cyan
    Write-Line "=" "Cyan"
    Write-Host "Workdir : $workdir"
    Write-Host "Command : gradlew.bat cleanTest test --tests `"$filter`" --console=plain -q"

    Push-Location $workdir
    $out  = & $gradle cleanTest test --tests $filter --console=plain -q 2>&1
    $code = $LASTEXITCODE
    Pop-Location

    # Doc ket qua that tu file JUnit XML (ten test case bat dau bang "INS-0x")
    $detail = ""
    $time   = "?"
    $status = if ($code -eq 0) { "PASS" } else { "FAIL" }
    if (Test-Path $xmlFile) {
        [xml]$xml = Get-Content $xmlFile -Encoding UTF8
        $tc = @($xml.testsuite.testcase) | Where-Object { $_.name -like "$($c.Id) *" } | Select-Object -First 1
        if ($tc) {
            $time = $tc.time
            if ($tc.failure) { $status = "FAIL"; $detail = ($tc.failure.message -split "`n")[0] }
            elseif ($tc.error) { $status = "FAIL"; $detail = ($tc.error.message -split "`n")[0] }
            else { $status = "PASS" }
        }
    }
    if ($status -eq "PASS") { Write-Host "1 passed in ${time}s" }
    else {
        Write-Host "1 failed in ${time}s" -ForegroundColor Red
        if ($detail) { Write-Host "Reason  : $detail" -ForegroundColor Red }
        elseif ($out) { $out | Select-Object -First 8 | ForEach-Object { Write-Host $_ -ForegroundColor Red } }
    }

    $color = if ($status -eq "PASS") { "Green" } else { "Red" }
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
