param(
    [ValidateSet('all', 'CP_01', 'CP_02', 'FM_00')]
    [string]$Tab = 'all',
    [switch]$PromptForCredentials
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Python = Join-Path $ProjectRoot '.venv-sqa\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $Python)) { throw "Python venv not found: $Python" }

$Targets = switch ($Tab) {
    'CP_01' { @('sqa/system_tests/test_cp_01.py') }
    'CP_02' { @('sqa/system_tests/test_cp_02.py') }
    'FM_00' { @('sqa/system_tests/test_fm_00.py') }
    default { @('sqa/system_tests/test_cp_01.py', 'sqa/system_tests/test_cp_02.py', 'sqa/system_tests/test_fm_00.py') }
}

Push-Location -LiteralPath $ProjectRoot
$DoctorSecret = $null
$ReceptionistSecret = $null
$DoctorPtr = [IntPtr]::Zero
$ReceptionistPtr = [IntPtr]::Zero
$SetDoctorPassword = $false
$SetReceptionistPassword = $false
try {
    if ($PromptForCredentials -and $Tab -in @('all', 'CP_01', 'CP_02') -and -not $env:SQA_DOCTOR_PASSWORD) {
        $DoctorSecret = Read-Host 'Password for doctor B test account' -AsSecureString
        $DoctorPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($DoctorSecret)
        $env:SQA_DOCTOR_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($DoctorPtr)
        $SetDoctorPassword = $true
    }
    if ($PromptForCredentials -and $Tab -in @('all', 'FM_00') -and -not $env:SQA_RECEPTIONIST_PASSWORD) {
        $ReceptionistSecret = Read-Host 'Password for receptionist test account' -AsSecureString
        $ReceptionistPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($ReceptionistSecret)
        $env:SQA_RECEPTIONIST_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ReceptionistPtr)
        $SetReceptionistPassword = $true
    }
    # -s keeps Selenium and pytest output visible in the terminal.
    & $Python -m pytest -s -v --junitxml=sqa\system_tests\results.xml $Targets
    exit $LASTEXITCODE
}
finally {
    if ($SetDoctorPassword) { Remove-Item Env:SQA_DOCTOR_PASSWORD -ErrorAction SilentlyContinue }
    if ($SetReceptionistPassword) { Remove-Item Env:SQA_RECEPTIONIST_PASSWORD -ErrorAction SilentlyContinue }
    if ($DoctorPtr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($DoctorPtr) }
    if ($ReceptionistPtr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ReceptionistPtr) }
    Pop-Location
}
