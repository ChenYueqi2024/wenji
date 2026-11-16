param([string]$ModelConfig = '', [int]$Port = 8765, [string]$Database = '')
$ErrorActionPreference = 'Stop'
$runtimeRoot = Split-Path $PSScriptRoot -Parent
if (-not $ModelConfig) { $ModelConfig = Join-Path $runtimeRoot 'config/models.local.json' }
if (-not (Test-Path -LiteralPath $ModelConfig)) { throw '请先复制 config/models.example.json 为 config/models.local.json，填写协议、API地址与模型名。密钥不要写入JSON。' }
$ModelConfig = (Resolve-Path -LiteralPath $ModelConfig).Path
$config = Get-Content -LiteralPath $ModelConfig -Raw -Encoding utf8 | ConvertFrom-Json
$profile = $config
if ($config.profiles) {
    $property = $config.profiles.PSObject.Properties[$config.active_profile]
    if (-not $property) { throw 'active_profile 必须指向一个已配置的模型档案。' }
    $profile = $property.Value
}
$keyName = [string]$profile.api_key_env
$temporaryKey = $false
try {
    if ($profile.auth_mode -ne 'none') {
        if ($keyName -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,127}$') { throw 'api_key_env 必须是合法的环境变量名。' }
        if (-not [Environment]::GetEnvironmentVariable($keyName, 'Process')) {
            Write-Host '请输入所选模型供应商的 API Key（输入隐藏，仅供此次服务进程使用；不是GitHub令牌）。'
            $secureKey = Read-Host -AsSecureString
            $credential = [System.Net.NetworkCredential]::new('', $secureKey)
            if (-not $credential.Password) { throw '未输入API Key，服务未启动。' }
            [Environment]::SetEnvironmentVariable($keyName, $credential.Password, 'Process')
            $temporaryKey = $true
            $credential = $null
            $secureKey.Dispose()
            $secureKey = $null
        }
    }
    & (Join-Path $PSScriptRoot 'run_service.ps1') -ModelConfig $ModelConfig -Port $Port -Database $Database
} finally {
    if ($temporaryKey) { [Environment]::SetEnvironmentVariable($keyName, $null, 'Process') }
    $credential = $null
    if ($secureKey) { $secureKey.Dispose(); $secureKey = $null }
}
