param(
    [switch]$BuildOnly
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$javaHome = 'D:\DevTools\Java\temurin-17'
$androidSdk = 'D:\DevTools\Android\android-sdk'
$gradle = 'D:\DevTools\Gradle\gradle-8.7\bin\gradle.bat'
$adb = Join-Path $androidSdk 'platform-tools\adb.exe'

foreach ($required in @(
    (Join-Path $javaHome 'bin\java.exe'),
    $gradle,
    $adb
)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Required Android preview tool not found: $required"
    }
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidSdk
$env:ANDROID_SDK_ROOT = $androidSdk

Push-Location $root
try {
    & $gradle :app:assemblePreview
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    if ($BuildOnly) {
        Write-Host 'Preview APK built successfully.'
        exit 0
    }

    $devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' })
    if ($devices.Count -ne 1) {
        throw "Connect exactly one Android phone with USB or wireless debugging. Connected devices: $($devices.Count)"
    }

    & $gradle :app:installPreview
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    & $adb shell am start -n 'com.mcpocket.poc.preview/com.mcpocket.poc.DashboardActivity'
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    Write-Host 'PickPico Preview is open on the connected phone.'
} finally {
    Pop-Location
}
