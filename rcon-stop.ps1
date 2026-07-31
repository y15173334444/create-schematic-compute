# RCON 优雅关闭 NeoForge 开发服务端
# Graceful-stop the NeoForge dev server via RCON (source RCON protocol).
#
# 用法 / Usage:
#   powershell -ExecutionPolicy Bypass -File rcon-stop.ps1
#   默认从本仓库 runs/server/server.properties 读取 rcon.port / rcon.password；
#   也可用参数覆盖：-Server 127.0.0.1 -Port 25575 -Password xxx
#   需服务端已启用 RCON 并重启生效：enable-rcon=true 且 rcon.password 非空。
#
# 说明：发送 save-all（先刷盘）再 stop（保存并退出）—— 与 CLAUDE.md 的优雅关闭规范一致。

param(
    [string]$Server = "127.0.0.1",
    [int]$Port = 0,
    [string]$Password = ""
)

$ErrorActionPreference = 'Stop'

# 从 runs/server/server.properties 读取 RCON 配置（无硬编码密码）
$propsFile = Join-Path $PSScriptRoot "runs\server\server.properties"
if ((Test-Path $propsFile) -and ($Port -eq 0 -or [string]::IsNullOrEmpty($Password))) {
    foreach ($line in Get-Content $propsFile) {
        if ($line -match '^\s*rcon\.port\s*=\s*(\d+)\s*$')  { if ($Port -eq 0) { $Port = [int]$Matches[1] } }
        if ($line -match '^\s*rcon\.password\s*=\s*(.+?)\s*$') { if ([string]::IsNullOrEmpty($Password)) { $Password = $Matches[1] } }
    }
}
if ($Port -le 0)   { Write-Error "RCON port not found (runs/server/server.properties) and -Port not given."; exit 1 }
if ([string]::IsNullOrEmpty($Password)) { Write-Error "RCON password not found (runs/server/server.properties) and -Password not given."; exit 1 }

$client = New-Object System.Net.Sockets.TcpClient
try {
    $client.Connect($Server, $Port)
} catch {
    Write-Error ("RCON connect failed: " + $_.Exception.Message + " — 服务端是否已启用 RCON 并重启生效？")
    exit 1
}
$stream = $client.GetStream()
$buf = New-Object byte[] 8192

function Send-Rcon {
    param([int]$Id, [int]$Type, [string]$Payload)
    $p = [System.Text.Encoding]::ASCII.GetBytes($Payload)
    $len = [BitConverter]::GetBytes([int](4 + 4 + $p.Length + 2))   # id + type + payload + \0\0
    $reqId = [BitConverter]::GetBytes([int]$Id)
    $t = [BitConverter]::GetBytes([int]$Type)
    $packet = New-Object byte[] ($len.Length + $reqId.Length + $t.Length + $p.Length + 2)
    [Array]::Copy($len, 0, $packet, 0, 4)
    [Array]::Copy($reqId, 0, $packet, 4, 4)
    [Array]::Copy($t, 0, $packet, 8, 4)
    [Array]::Copy($p, 0, $packet, 12, $p.Length)
    $stream.Write($packet, 0, $packet.Length)
    $stream.Flush()
    $n = $stream.Read($buf, 0, $buf.Length)
    if ($n -lt 12) { throw "Short RCON response ($n bytes)" }
    $respLen = [BitConverter]::ToInt32($buf, 0)
    $respId  = [BitConverter]::ToInt32($buf, 4)
    $respType = [BitConverter]::ToInt32($buf, 8)
    $payloadLen = [Math]::Max(0, $n - 12)
    $respPayload = [System.Text.Encoding]::ASCII.GetString($buf, 12, $payloadLen).TrimEnd([char]0)
    return @{ id = $respId; type = $respType; payload = $respPayload }
}

try {
    $login = Send-Rcon -Id 1 -Type 3 -Payload $Password      # 3 = login
    if ($login.id -ne 1) {
        Write-Error "RCON auth failed (wrong password or RCON not enabled yet)."
        exit 1
    }
    Write-Output "RCON authenticated to ${Server}:${Port}"

    $r1 = Send-Rcon -Id 2 -Type 2 -Payload "save-all"         # 2 = command
    Write-Output ("save-all -> " + $r1.payload)

    $r2 = Send-Rcon -Id 3 -Type 2 -Payload "stop"
    Write-Output ("stop -> " + $r2.payload)

    Write-Output "Stop command sent. Server should save and shut down gracefully."
    Start-Sleep -Seconds 2
} finally {
    $client.Close()
}
