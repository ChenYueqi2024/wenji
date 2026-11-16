$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'python_runtime.ps1')
$pythonExe = Resolve-WenjiPython
$runtimeRoot = Split-Path $PSScriptRoot -Parent
$reportRoot = Join-Path $runtimeRoot 'reports'
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
Push-Location $runtimeRoot
try {
    & $pythonExe -X utf8 -m unittest discover -s tests -v 2>&1 | Tee-Object -FilePath (Join-Path $reportRoot 'unit-tests.log')
    if ($LASTEXITCODE -ne 0) { throw '自动化测试失败，查看 reports/unit-tests.log。' }
    & $pythonExe -X utf8 scripts/http_smoke.py 2>&1 | Tee-Object -FilePath (Join-Path $reportRoot 'http-smoke.log')
    if ($LASTEXITCODE -ne 0) { throw 'HTTP集成测试失败，查看 reports/http-smoke.log。' }
} finally { Pop-Location }
