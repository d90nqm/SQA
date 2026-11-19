# ============================================================================
# TT sheet (Postman) - chay TUNG test case rieng bang Newman, hien thi ro rang
#
# Cach chay (PowerShell, tai thu muc goc project):
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-postman-execution.ps1 -Target ALL
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-postman-execution.ps1 -Target PAY-03
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-postman-execution.ps1 -Target BOT-API-04
#
# Yeu cau:
#   - npm install -g newman newman-reporter-htmlextra
#   - He thong dang chay: gateway (http://localhost:8080) + invoice-service + payment-service
#     (cho PAY-xx; hoa don tam duoc tao ngay trong PAY-01) va chatbot-service (http://localhost:9992) cho BOT-API-xx.
#
# Cac case co du lieu noi tiep nhau (vd PAY-03 can invoice/payment tao o PAY-01 va PAY-02),
# nen khi chay 1 case, script chay cac case truoc do trong cung collection.
# ============================================================================
param(
    [string]$Target = "ALL",
    [string]$Newman = "newman"
)

$ErrorActionPreference = "Continue"
$root = (Resolve-Path "$PSScriptRoot\..").Path

$groups = @(
    @{
        Name = "Payment"; Service = "payment-service"
        Dir = Join-Path $root "tests\postman\Payment"
        Collection = "Payment.postman_collection.json"
        Environment = "Payment_Local.postman_environment.json"
        Report = "payment_report.html"
        Cases = @(
            @{ Id = "PAY-01";     Folder = "PAY-01 Create bank transfer payment" },
            @{ Id = "PAY-02";     Folder = "PAY-02 Callback success" },
            @{ Id = "PAY-03";     Folder = "PAY-03 Get payment status" },
            @{ Id = "PAY-04";     Folder = "PAY-04 Get payment by id" }
        )
    },
    @{
        Name = "Chatbot"; Service = "chatbot-service"
        Dir = Join-Path $root "tests\postman\Chatbot"
        Collection = "Chatbot.postman_collection.json"
        Environment = "Chatbot_Local.postman_environment.json"
        Report = "chatbot_report.html"
        Cases = @(
            @{ Id = "BOT-API-01"; Folder = "BOT-API-01 Health" },
            @{ Id = "BOT-API-02"; Folder = "BOT-API-02 Chat success" },
            @{ Id = "BOT-API-03"; Folder = "BOT-API-03 Chat invalid or empty" },
            @{ Id = "BOT-API-04"; Folder = "BOT-API-04 History" }
        )
    }
)

function Write-Line([string]$ch, [string]$color) {
    Write-Host ($ch * 78) -ForegroundColor $color
}

if (-not (Get-Command $Newman -ErrorAction SilentlyContinue)) {
    Write-Host "Khong tim thay newman. Cai bang: npm install -g newman newman-reporter-htmlextra" -ForegroundColor Red
    exit 3
}

# Chon cac case can chay (ALL, hoac tu dau collection den case duoc chon)
$plan = @()
foreach ($g in $groups) {
    $ids = @($g.Cases | ForEach-Object { $_.Id })
    if ($Target -eq "ALL") {
        $plan += [pscustomobject]@{ Group = $g; Cases = $g.Cases }
    }
    elseif ($ids -contains $Target) {
        $idx = [array]::IndexOf($ids, $Target)
        $plan += [pscustomobject]@{ Group = $g; Cases = @($g.Cases[0..$idx]) }
    }
}
if (-not $plan) {
    Write-Host "Target '$Target' khong hop le. Dung ALL, PAY-01..PAY-04 hoac BOT-API-01..BOT-API-04" -ForegroundColor Red
    exit 2
}

$summary = @()
foreach ($p in $plan) {
    $g = $p.Group
    $reports = Join-Path $g.Dir "reports"
    New-Item -ItemType Directory -Force -Path $reports | Out-Null
    $runtimeEnv = Join-Path $reports "runtime_environment.json"
    Copy-Item (Join-Path $g.Dir $g.Environment) $runtimeEnv -Force

    foreach ($c in $p.Cases) {
        Write-Host ""
        Write-Line "=" "Cyan"
        Write-Host "RUNNING :: $($g.Service) execution :: $($c.Id)" -ForegroundColor Cyan
        Write-Line "=" "Cyan"
        Write-Host ""
        Write-Host "Workdir : $($g.Dir)"
        $htmlOut = Join-Path $reports ("{0}_report.html" -f $c.Id)
        Write-Host "Command : newman run $($g.Collection) -e reports\runtime_environment.json --export-environment reports\runtime_environment.json --folder `"$($c.Folder)`" -r cli,htmlextra"

        Push-Location $g.Dir
        & $Newman run $g.Collection `
            -e $runtimeEnv --export-environment $runtimeEnv `
            --folder $c.Folder `
            -r "cli,htmlextra" --reporter-htmlextra-export $htmlOut --reporter-htmlextra-title "$($c.Id)"
        $code = $LASTEXITCODE
        Pop-Location

        $status = if ($code -eq 0) { "PASS" } else { "FAIL" }
        $color  = if ($status -eq "PASS") { "Green" } else { "Red" }
        Write-Host ""
        Write-Line "#" $color
        Write-Host "RESULT: $status" -ForegroundColor $color
        Write-Host "TARGET: $($g.Service) execution :: $($c.Id)" -ForegroundColor $color
        Write-Host "CODE  : $code" -ForegroundColor $color
        Write-Host "REPORT: $htmlOut" -ForegroundColor $color
        Write-Line "#" $color
        Write-Host ""

        $summary += [pscustomobject]@{ ID = $c.Id; Result = $status }
    }

    # Chay ALL: xuat them 1 bao cao HTML tong cua ca collection
    if ($Target -eq "ALL") {
        $full = Join-Path $reports $g.Report
        Push-Location $g.Dir
        & $Newman run $g.Collection -e (Join-Path $g.Dir $g.Environment) `
            -r htmlextra --reporter-htmlextra-export $full --reporter-htmlextra-title "$($g.Name) collection" | Out-Null
        Pop-Location
        Write-Host "Full collection report: $full" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Line "=" "Cyan"
Write-Host "SUMMARY :: postman execution ($($summary.Count) test case)" -ForegroundColor Cyan
Write-Line "=" "Cyan"
$summary | Format-Table -AutoSize
$pass = @($summary | Where-Object Result -eq "PASS").Count
Write-Host "Pass: $pass   Fail: $($summary.Count - $pass)"