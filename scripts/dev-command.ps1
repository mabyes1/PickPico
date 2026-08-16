param(
    [Parameter(Mandatory = $true)]
    [string]$CommandId,

    [string]$ArgumentsJson = '{}'
)

$ErrorActionPreference = 'Stop'
$adb = 'D:\DevTools\Android\platform-tools\adb.exe'

$bytes = [System.Text.Encoding]::UTF8.GetBytes($ArgumentsJson)
$argumentsBase64 = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')

& $adb shell am start-foreground-service `
    -n com.mcpocket.poc/.DevBridgeService `
    --es action mcp_command `
    --es commandId $CommandId `
    --es argumentsBase64 $argumentsBase64

if ($LASTEXITCODE -ne 0) {
    throw "Failed to request MCPocket dev MCP command"
}

Start-Sleep -Milliseconds 900
& $adb shell run-as com.mcpocket.poc cat files/dev-last-command.json
