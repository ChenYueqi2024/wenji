param([string]$DeviceId = '', [switch]$IncludeFixtures)
$ErrorActionPreference = 'Stop'
$runtimeDir = Split-Path $PSScriptRoot -Parent
$adbPath = Join-Path $runtimeDir 'toolchain/sdk/platform-tools/adb.exe'
if (-not (Test-Path $adbPath)) { $adbPath = (Get-Command adb -ErrorAction Stop).Source }
$apkPath = Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path $apkPath)) { $apkPath = Join-Path (Split-Path $runtimeDir -Parent) 'apk/app-debug.apk' }
if (-not (Test-Path $apkPath)) { throw '未找到调试APK，请先运行 build-local.ps1，或使用包含apk目录的完整交付包。' }
$devices = @(& $adbPath devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($DeviceId) {
    if ($DeviceId -notin $devices) { throw '指定设备未连接或未授权USB调试。' }
} elseif ($devices.Count -eq 1) { $DeviceId = $devices[0] }
else { throw '需要一台已授权的Android设备；多设备时请使用 -DeviceId 指定。' }
& $adbPath -s $DeviceId install -r $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK安装失败。未清除设备已有应用数据。' }
if ($IncludeFixtures) {
    & $adbPath -s $DeviceId shell mkdir -p /sdcard/Download/Wenji
    & $adbPath -s $DeviceId push (Join-Path $runtimeDir 'fixtures/resource_graph_v1.pdf') /sdcard/Download/Wenji/
    if ($LASTEXITCODE -ne 0) { throw '合成教学PDF复制失败。' }
}
Write-Output '手机独立版APK已安装，原应用数据保留。直接打开问迹即可使用，不需要电脑服务或端口转发；也可在资料页导入内置教材。'
