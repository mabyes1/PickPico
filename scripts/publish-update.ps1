param(
    [string]$RelayBaseUrl = 'https://relay.mcpocket.workers.dev',
    [string]$KvBinding = 'UPDATE_KV',
    [int]$ChunkSizeMiB = 20,
    [switch]$SkipBuild,
    [switch]$SkipDeploy
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $root 'app\build.gradle'
$relayDir = Join-Path $root 'relay'
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot 'build-debug.ps1')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if (-not (Test-Path -LiteralPath $apk)) {
    throw "APK not found: $apk"
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
$assetName = "mcpocket-$versionName-$versionCode.apk"
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

$manifestPath = Join-Path ([System.IO.Path]::GetTempPath()) 'mcpocket-update-latest.json'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json), $utf8NoBom)
$metadataPath = Join-Path ([System.IO.Path]::GetTempPath()) 'mcpocket-update-metadata.json'
$chunkDir = Join-Path ([System.IO.Path]::GetTempPath()) ("mcpocket-update-" + [Guid]::NewGuid().ToString('N'))
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
        npx wrangler deploy
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    for ($index = 0; $index -lt $chunkPaths.Count; $index++) {
        $chunkKey = "$objectKey`:chunk:{0:D3}" -f $index
        npx wrangler kv key put $chunkKey --path $chunkPaths[$index] --binding $KvBinding --remote
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    npx wrangler kv key put "$objectKey`:meta" --path $metadataPath --binding $KvBinding --remote
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    npx wrangler kv key put 'latest.json' --path $manifestPath --binding $KvBinding --remote
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
    Remove-Item -LiteralPath $manifestPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $metadataPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $chunkDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Published MCPocket $versionName ($versionCode)"
Write-Host "Manifest: $relayBaseUrl/v1/update/latest"
Write-Host "APK:      $apkUrl"
Write-Host "SHA-256:  $sha256"
