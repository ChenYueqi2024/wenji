param([string[]]$Tasks = @('assembleDebug', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$androidDir = $PSScriptRoot
$toolchainDir = Join-Path (Split-Path $androidDir -Parent) 'toolchain'
$portableJava = Get-ChildItem -LiteralPath $toolchainDir -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($portableJava) { $env:JAVA_HOME = $portableJava.FullName }
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { throw '需要 JDK17。请设置 JAVA_HOME 或使用 Android Studio 的 Gradle JDK。' }
if (Test-Path (Join-Path $toolchainDir 'sdk')) {
    $env:ANDROID_HOME = Join-Path $toolchainDir 'sdk'
    ('sdk.dir=' + $env:ANDROID_HOME.Replace('\','/')) | Set-Content -LiteralPath (Join-Path $androidDir 'local.properties') -Encoding utf8
}
$env:GRADLE_USER_HOME = Join-Path $toolchainDir 'gradle-cache'
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
$portableGradle = Join-Path $toolchainDir 'gradle-8.11.1/bin/gradle.bat'
$gradleCmd = if (Test-Path $portableGradle) { $portableGradle } else { Join-Path $androidDir 'gradlew.bat' }
Push-Location $androidDir
try { & $gradleCmd --no-daemon --console=plain @Tasks; if ($LASTEXITCODE -ne 0) { throw "Gradle exited $LASTEXITCODE" } }
finally { Pop-Location }
