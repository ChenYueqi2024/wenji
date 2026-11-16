param([int]$AdbPort = 16384)
$ErrorActionPreference = 'Stop'
$runtimeRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'python_runtime.ps1')
$pythonExe = Resolve-WenjiPython
$adb = Join-Path $runtimeRoot 'toolchain/sdk/platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { $adb = (Get-Command adb -ErrorAction Stop).Source }
$device = "127.0.0.1:$AdbPort"
& $adb connect $device
if ($LASTEXITCODE -ne 0) { throw '无法连接MuMu，请先启动模拟器并确认本地ADB端口。' }
& $adb -s $device get-state
if ($LASTEXITCODE -ne 0) { throw '模拟器未就绪或未授权ADB。' }
$reportDir = Join-Path $runtimeRoot 'reports/mumu'
New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
$health = $null
try { $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/health' -TimeoutSec 2 } catch {}
if (-not $health) {
    $serviceProcess = Start-Process -FilePath $pythonExe -ArgumentList @('-X','utf8','-m','server','--db','data/mumu-demo.sqlite3','--port','8765') -WorkingDirectory $runtimeRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $reportDir 'service.stdout.log') -RedirectStandardError (Join-Path $reportDir 'service.stderr.log') -PassThru
    $serviceProcess.Id | Set-Content -LiteralPath (Join-Path $reportDir 'service.pid')
    Start-Sleep -Seconds 2
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/health' -TimeoutSec 3
}
if ($health.authentication -ne 'none-loopback-development-only' -or $health.mode -notin @('local-demo','local-llm')) { throw '8765端口不是预期的问迹开发服务，请检查占用。' }
& (Join-Path $runtimeRoot 'android/install-debug.ps1') -DeviceId $device -IncludeFixtures
& $adb -s $device shell am start -n org.wenji.study/.MainActivity
if ($LASTEXITCODE -ne 0) { throw '应用启动失败。' }
Write-Output '问迹已连接MuMu；本地服务在后台运行，合成PDF在Download/Wenji。界面与完整流程仍须实际检查。'
