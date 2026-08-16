$ErrorActionPreference = 'Stop'

$jdk = 'D:\DevTools\PhoneMonitorAndroid\jdk-17'
$gradle = 'D:\DevTools\PhoneMonitorAndroid\gradle-8.7\bin\gradle.bat'

if (-not (Test-Path -LiteralPath $jdk)) {
    throw "Java 17 not found at $jdk"
}
if (-not (Test-Path -LiteralPath $gradle)) {
    throw "Gradle 8.7 not found at $gradle"
}

$env:JAVA_HOME = $jdk
& $gradle :app:testDebugUnitTest :app:assembleDebug
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
