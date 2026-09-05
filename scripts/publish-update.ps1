param(
    [string]$RelayBaseUrl = $env:PICKPICO_RELAY_BASE_URL,
    [string]$KvBinding = 'UPDATE_KV',
    [string]$WranglerConfig = '',
    [int]$ChunkSizeMiB = 20,
    [switch]$SkipBuild,
    [switch]$SkipDeploy
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RelayBaseUrl)) {
    throw 'Specify your own Relay with -RelayBaseUrl or PICKPICO_RELAY_BASE_URL.'
}

# The current stable OTA channel is signed with the interactive Windows user's
# Android debug keystore. Running this script from Session 0 (for example the
# coding-tools Windows service) causes Gradle to create/use a different debug
# keystore, producing an APK Android will reject as a signature mismatch.
# Keep publishing in the signed-in desktop session until PickPico moves to a
# dedicated release signing key.
if ([System.Diagnostics.Process]::GetCurrentProcess().SessionId -eq 0) {
    throw 'PickPico OTA publish must run in the signed-in Windows user session, not Session 0. Use an active-user execution context so the existing Android signing key and Wrangler login are reused.'
}

$root = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $root 'app\build.gradle'
$relayDir = Join-Path $root 'relay'
if ([string]::IsNullOrWhiteSpace($WranglerConfig)) {
    $privateConfig = Join-Path $relayDir 'wrangler.local.jsonc'
    $WranglerConfig = if (Test-Path -LiteralPath $privateConfig) { $privateConfig } else { Join-Path $relayDir 'wrangler.jsonc' }
}
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
$jdk = 'D:\DevTools\PhoneMonitorAndroid\jdk-17'
$androidSdk = 'D:\DevTools\PhoneMonitorAndroid\android-sdk'
$apksigner = Join-Path $androidSdk 'build-tools\35.0.0\apksigner.bat'
$keytool = Join-Path $jdk 'bin\keytool.exe'

function Resolve-PickPicoSigningKeystore {
    if (-not [string]::IsNullOrWhiteSpace($env:PICKPICO_SIGNING_KEYSTORE)) {
        return $env:PICKPICO_SIGNING_KEYSTORE
    }
    return Join-Path $env:USERPROFILE '.android\debug.keystore'
}

function Get-KeystoreCertificateSha256 {
    param([string]$KeystorePath)
    $temporaryCertificate = Join-Path ([System.IO.Path]::GetTempPath()) ("pickpico-cert-" + [Guid]::NewGuid().ToString('N') + '.der')
    try {
        & $keytool -exportcert -alias androiddebugkey -keystore $KeystorePath -storepass android -file $temporaryCertificate | Out-Null
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $temporaryCertificate)) {
            throw 'Unable to export the PickPico signing certificate.'
        }
        $bytes = [System.IO.File]::ReadAllBytes($temporaryCertificate)
        $digest = [System.Security.Cryptography.SHA256]::Create()
        try {
            return ([BitConverter]::ToString($digest.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
        } finally {
            $digest.Dispose()
        }
    } finally {
        Remove-Item -LiteralPath $temporaryCertificate -Force -ErrorAction SilentlyContinue
    }
}

function Get-ApkCertificateSha256 {
    param([string]$ApkPath)
    $lines = & $apksigner verify --print-certs $ApkPath
    if ($LASTEXITCODE -ne 0) {
        throw 'apksigner could not verify the PickPico APK.'
    }
    $match = [regex]::Match(($lines -join "`n"), 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
    if (-not $match.Success) {
        throw 'Unable to read the APK signing certificate SHA-256 digest.'
    }
    return $match.Groups[1].Value.ToLowerInvariant()
}

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot 'build-debug.ps1')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if (-not (Test-Path -LiteralPath $apk)) {
    throw "APK not found: $apk"
}
if (-not (Test-Path -LiteralPath $keytool)) {
    throw "keytool not found: $keytool"
}
if (-not (Test-Path -LiteralPath $apksigner)) {
    throw "apksigner not found: $apksigner"
}

$signingKeystore = Resolve-PickPicoSigningKeystore
if (-not (Test-Path -LiteralPath $signingKeystore)) {
    throw "PickPico signing keystore not found: $signingKeystore"
}
$expectedCertificateSha256 = Get-KeystoreCertificateSha256 $signingKeystore
$apkCertificateSha256 = Get-ApkCertificateSha256 $apk
if ($apkCertificateSha256 -ne $expectedCertificateSha256) {
    throw "Refusing to publish PickPico: APK signing certificate $apkCertificateSha256 does not match expected certificate $expectedCertificateSha256. Rebuild with scripts/build-debug.ps1 in a compatible signing context."
}

$gradleText = Get-Content -LiteralPath $gradle -Raw
$versionNameMatch = [regex]::Match($gradleText, "versionName\s+'([^']+)'")
$versionCodeMatch = [regex]::Match($gradleText, 'versionCode\s+(\d+)')
if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success) {
    throw 'Unable to read versionName/versionCode from app/build.gradle'
}

$versionName = $versionNameMatch.Groups[1].Value
$versionCode = [int64]$versionCodeMatch.Groups[1].Value
$sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$assetName = "pickpico-$versionName-$versionCode.apk"
$objectKey = "releases/$assetName"
$relayBaseUrl = $RelayBaseUrl.TrimEnd('/')
$apkUrl = "$relayBaseUrl/v1/update/files/$objectKey"

$manifest = [ordered]@{
    channel = 'stable'
    versionName = $versionName
    versionCode = $versionCode
    apkUrl = $apkUrl
    sha256 = $sha256
    publishedAt = [DateTimeOffset]::UtcNow.ToString('o')
}

$manifestPath = Join-Path ([System.IO.Path]::GetTempPath()) 'pickpico-update-latest.json'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json), $utf8NoBom)
$metadataPath = Join-Path ([System.IO.Path]::GetTempPath()) 'pickpico-update-metadata.json'
$chunkDir = Join-Path ([System.IO.Path]::GetTempPath()) ("pickpico-update-" + [Guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($chunkDir)

$chunkSize = [int64]$ChunkSizeMiB * 1024L * 1024L
if ($chunkSize -lt 1MB -or $chunkSize -gt 24MB) {
    throw 'ChunkSizeMiB must produce chunks between 1 MiB and 24 MiB for Workers KV.'
}

$chunkPaths = @()
$input = [System.IO.File]::OpenRead($apk)
try {
    $index = 0
    $buffer = New-Object byte[] $chunkSize
    while (($read = $input.Read($buffer, 0, $buffer.Length)) -gt 0) {
        $chunkPath = Join-Path $chunkDir ("chunk-{0:D3}.bin" -f $index)
        $output = [System.IO.File]::Create($chunkPath)
        try {
            $output.Write($buffer, 0, $read)
        } finally {
            $output.Dispose()
        }
        $chunkPaths += $chunkPath
        $index++
    }
} finally {
    $input.Dispose()
}

$metadata = [ordered]@{
    chunkCount = $chunkPaths.Count
    totalBytes = (Get-Item -LiteralPath $apk).Length
    sha256 = $sha256
}
[System.IO.File]::WriteAllText($metadataPath, ($metadata | ConvertTo-Json), $utf8NoBom)

Push-Location $relayDir
try {
    if (-not $SkipDeploy) {
        npx wrangler deploy --config $WranglerConfig
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    for ($index = 0; $index -lt $chunkPaths.Count; $index++) {
        $chunkKey = "$objectKey`:chunk:{0:D3}" -f $index
        npx wrangler kv key put $chunkKey --path $chunkPaths[$index] --binding $KvBinding --remote --config $WranglerConfig
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    npx wrangler kv key put "$objectKey`:meta" --path $metadataPath --binding $KvBinding --remote --config $WranglerConfig
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    npx wrangler kv key put 'latest.json' --path $manifestPath --binding $KvBinding --remote --config $WranglerConfig
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
    Remove-Item -LiteralPath $manifestPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $metadataPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $chunkDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Published PickPico $versionName ($versionCode)"
Write-Host "Manifest: $relayBaseUrl/v1/update/latest"
Write-Host "APK:      $apkUrl"
Write-Host "SHA-256:  $sha256"
