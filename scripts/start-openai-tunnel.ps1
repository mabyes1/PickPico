param(
    [Parameter(Mandatory = $true)][uri]$McpUrl,
    [string]$TunnelId = $env:CONTROL_PLANE_TUNNEL_ID,
    [string]$TunnelClient = 'tunnel-client',
    [switch]$CheckPhoneOnly
)

$ErrorActionPreference = 'Stop'
if ($McpUrl.Scheme -notin @('http', 'https') -or $McpUrl.UserInfo -or $McpUrl.Query -or $McpUrl.Fragment) {
    throw 'Use the Local MCP HTTP/HTTPS URL, without credentials or query parameters.'
}
if (-not $CheckPhoneOnly) {
    $client = Get-Command $TunnelClient -ErrorAction Stop
    if ([string]::IsNullOrWhiteSpace($TunnelId)) { $TunnelId = Read-Host 'OpenAI tunnel ID' }
    if ($TunnelId -notmatch '^tunnel_[A-Za-z0-9_-]+$') { throw 'Invalid tunnel ID.' }
}

function Read-PrivateValue([string]$Prompt) {
    $secret = Read-Host $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $secret.Dispose()
    }
}

$oldAuthorization = $env:PICKPICO_TUNNEL_AUTHORIZATION
$oldApiKey = $env:CONTROL_PLANE_API_KEY
try {
    $token = Read-PrivateValue 'PickPico Local bearer (hidden)'
    if ([string]::IsNullOrWhiteSpace($token) -or $token -match '[\r\n]') { throw 'Invalid Local bearer.' }
    $env:PICKPICO_TUNNEL_AUTHORIZATION = 'Bearer ' + $token.Trim()
    $token = $null
    $headers = @{
        Authorization = $env:PICKPICO_TUNNEL_AUTHORIZATION
        Accept = 'application/json, text/event-stream'
        'x-pickpico-tool-profile' = 'thin-v1'
    }
    $body = @{ jsonrpc = '2.0'; id = 1; method = 'tools/list'; params = @{} } | ConvertTo-Json -Compress
    $reply = Invoke-RestMethod -Uri $McpUrl.AbsoluteUri -Method Post -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 10
    if ($reply.error -or -not ($reply.result.tools.name -contains 'command_run')) {
        throw 'Local MCP did not return the expected PickPico command_run tool.'
    }
    Write-Host 'Local PickPico MCP authenticated and tool discovery passed.'
    if ($CheckPhoneOnly) { return }

    if ([string]::IsNullOrWhiteSpace($env:CONTROL_PLANE_API_KEY)) {
        $env:CONTROL_PLANE_API_KEY = Read-PrivateValue 'OpenAI runtime API key (hidden; not an admin key)'
    }
    if ([string]::IsNullOrWhiteSpace($env:CONTROL_PLANE_API_KEY)) { throw 'Runtime API key is required.' }
    Write-Host 'Starting Tunnel. Keep this terminal and computer running. Stop with Ctrl+C.'
    Write-Host 'Local status page: http://127.0.0.1:18765/ui'
    & $client.Source run `
        --control-plane.tunnel-id $TunnelId `
        --control-plane.api-key 'env:CONTROL_PLANE_API_KEY' `
        --mcp.server-url $McpUrl.AbsoluteUri `
        --mcp.extra-headers 'Authorization: env:PICKPICO_TUNNEL_AUTHORIZATION' `
        --mcp.discovery-extra-headers 'Authorization: env:PICKPICO_TUNNEL_AUTHORIZATION' `
        --mcp.extra-headers 'x-pickpico-tool-profile: thin-v1' `
        --health.listen-addr '127.0.0.1:18765'
    if ($LASTEXITCODE -ne 0) { throw "tunnel-client exited with code $LASTEXITCODE" }
}
finally {
    $env:PICKPICO_TUNNEL_AUTHORIZATION = $oldAuthorization
    $env:CONTROL_PLANE_API_KEY = $oldApiKey
    $token = $null
    $headers = $null
}
