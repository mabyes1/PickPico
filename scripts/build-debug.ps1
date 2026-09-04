param(
    [string]$SigningKeystore = $env:PICKPICO_SIGNING_KEYSTORE
)

$ErrorActionPreference = 'Stop'

$jdk = 'D:\DevTools\PhoneMonitorAndroid\jdk-17'
$gradle = 'D:\DevTools\PhoneMonitorAndroid\gradle-8.7\bin\gradle.bat'

function Resolve-PickPicoSigningKeystore {
    param([string]$ExplicitPath)

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        return $ExplicitPath
    }
    return Join-Path $env:USERPROFILE '.android\debug.keystore'
}

if (-not (Test-Path -LiteralPath $jdk)) {
    throw "Java 17 not found at $jdk"
}
if (-not (Test-Path -LiteralPath $gradle)) {
    throw "Gradle 8.7 not found at $gradle"
}

if (([System.Diagnostics.Process]::GetCurrentProcess().SessionId -eq 0) -and [string]::IsNullOrWhiteSpace($SigningKeystore)) {
    throw 'PickPico signed builds must run in the signed-in Windows user session. Session 0 cannot read the user signing key and must not create publishable APK artifacts.'
}

$SigningKeystore = Resolve-PickPicoSigningKeystore $SigningKeystore
if ([string]::IsNullOrWhiteSpace($SigningKeystore) -or -not (Test-Path -LiteralPath $SigningKeystore)) {
    throw 'PickPico signing keystore was not found. Set PICKPICO_SIGNING_KEYSTORE or pass -SigningKeystore.'
}

$env:JAVA_HOME = $jdk
& $gradle "-PpickpicoDebugKeystore=$SigningKeystore" :app:testDebugUnitTest :app:assembleDebug
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
