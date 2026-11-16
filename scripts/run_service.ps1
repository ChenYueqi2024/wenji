param([int]$Port = 8765, [string]$Database = '', [string]$ModelConfig = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'python_runtime.ps1')
$pythonExe = Resolve-WenjiPython
$runtimeRoot = Split-Path $PSScriptRoot -Parent
if (-not $Database) { $Database = Join-Path $runtimeRoot 'data/wenji.sqlite3' }
if ($ModelConfig) { $ModelConfig = (Resolve-Path -LiteralPath $ModelConfig).Path }
New-Item -ItemType Directory -Path (Split-Path $Database -Parent) -Force | Out-Null
Push-Location $runtimeRoot
try {
    Write-Host "问迹开发服务：http://127.0.0.1:$Port（本机单用户；按 Ctrl+C 停止）"
    $serverArgs = @('-X', 'utf8', '-m', 'server', '--db', $Database, '--port', $Port)
    if ($ModelConfig) { $serverArgs += @('--model-config', $ModelConfig) }
    & $pythonExe @serverArgs
    if ($LASTEXITCODE -ne 0) { throw "服务退出，代码：$LASTEXITCODE" }
} finally { Pop-Location }
