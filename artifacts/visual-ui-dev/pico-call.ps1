function Invoke-Pico {
    param([string]$Tool, [hashtable]$Arguments = @{}, [string]$SaveName = '')
    $picoEndpoint = 'https://relay.pickpico.workers.dev/v3/nodes/wjLVc8x8sGvNvNRzjYOhtQ/mcp'
    $request = @{jsonrpc='2.0';id=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();method='tools/call';params=@{name=$Tool;arguments=$Arguments}}
    $requestJson = $request | ConvertTo-Json -Depth 60 -Compress
    $reply = Invoke-RestMethod -Method Post -Uri $picoEndpoint -ContentType 'application/json; charset=utf-8' -Headers @{Accept='application/json, text/event-stream'} -Body ([Text.Encoding]::UTF8.GetBytes($requestJson)) -TimeoutSec 90 -ErrorAction Stop
    if ($SaveName) {
        $out = Join-Path $PSScriptRoot ($SaveName + '.json')
        [IO.File]::WriteAllText($out, ($reply | ConvertTo-Json -Depth 80), [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $PSScriptRoot ($SaveName + '.request.json')), $requestJson, [Text.UTF8Encoding]::new($false))
        $imageIndex = 0
        foreach ($part in $reply.result.content) {
            if ($part.type -eq 'image') {
                $imageFile = Join-Path $PSScriptRoot ($SaveName + '-' + $imageIndex + '.jpg')
                [IO.File]::WriteAllBytes($imageFile, [Convert]::FromBase64String($part.data))
                $imageIndex++
            }
        }
    }
    if ($null -ne $reply.error) { throw ($reply.error | ConvertTo-Json -Compress -Depth 8) }
    if ($null -ne $reply.result.structuredContent) { return $reply.result.structuredContent }
    foreach ($part in $reply.result.content) {
        if ($part.type -eq 'text') { try { return ($part.text | ConvertFrom-Json) } catch { return $part.text } }
    }
    return $reply.result
}
