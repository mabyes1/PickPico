$ErrorActionPreference = 'Stop'

$adb = 'D:\DevTools\Android\platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    throw "ADB not found: $adb"
}

& $adb shell am start-foreground-service `
    -n com.mcpocket.poc/.DevBridgeService `
    --es action start_node

if ($LASTEXITCODE -ne 0) {
    throw "Failed to request MCPocket dev node start"
}

Start-Sleep -Milliseconds 1200
& $adb shell dumpsys activity services com.mcpocket.poc/.McpNodeService
