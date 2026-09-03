$ErrorActionPreference = 'Stop'

$connectionText = Get-Clipboard -Raw
if ([string]::IsNullOrWhiteSpace($connectionText)) {
    throw 'Clipboard is empty. Copy PickPico connection JSON first.'
}

$connection = $connectionText | ConvertFrom-Json
$url = [string]$connection.url

if ([string]::IsNullOrWhiteSpace($url)) {
    throw 'Clipboard JSON does not contain url.'
}

$headers = @{
    Accept = 'application/json'
    'Content-Type' = 'application/json'
}
$authorization = [string]$connection.headers.Authorization
if (-not [string]::IsNullOrWhiteSpace($authorization)) {
    if (-not $authorization.StartsWith('Bearer ')) {
        throw 'Clipboard headers.Authorization is not a Bearer token.'
    }
    $headers.Authorization = $authorization
}

$body = @{
    jsonrpc = '2.0'
    id = 1
    method = 'tools/call'
    params = @{
        name = 'app_update_latest'
        arguments = @{}
    }
} | ConvertTo-Json -Depth 10

$response = Invoke-RestMethod -Method Post -Uri $url -Headers $headers -Body $body -TimeoutSec 90
$result = $response.result.structuredContent
if ($null -eq $result) {
    $response | ConvertTo-Json -Depth 10
    exit 0
}

$result | ConvertTo-Json -Depth 10
