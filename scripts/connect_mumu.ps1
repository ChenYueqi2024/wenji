param([int]$AdbPort = 16384)
$ErrorActionPreference = 'Stop'
$runtimeRoot = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $runtimeRoot 'toolchain/sdk/platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { $adb = (Get-Command adb -ErrorAction Stop).Source }
$device = "127.0.0.1:$AdbPort"
& $adb connect $device
if ($LASTEXITCODE -ne 0) { throw '无法连接MuMu，请先启动模拟器并确认本地ADB端口。' }
& $adb -s $device get-state
if ($LASTEXITCODE -ne 0) { throw '模拟器未就绪或未授权ADB。' }
& (Join-Path $runtimeRoot 'android/install-debug.ps1') -DeviceId $device -IncludeFixtures
& $adb -s $device shell am start -n org.wenji.study/.MainActivity
if ($LASTEXITCODE -ne 0) { throw '应用启动失败。' }
Write-Output '问迹手机独立版已在MuMu启动，无需电脑服务；合成教材已内置，也可在Download/Wenji选择PDF。'
