function Resolve-WenjiPython {
    if ($env:WENJI_PYTHON -and (Test-Path -LiteralPath $env:WENJI_PYTHON)) { return $env:WENJI_PYTHON }
    $bundled = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
    if (Test-Path -LiteralPath $bundled) { return $bundled }
    $command = Get-Command python -ErrorAction SilentlyContinue
    if ($command -and $command.Source -notmatch 'Microsoft\\WindowsApps') { return $command.Source }
    throw '未找到 Python 3.11+。请安装 Python，或设置 WENJI_PYTHON 为 python.exe 的绝对路径。'
}
