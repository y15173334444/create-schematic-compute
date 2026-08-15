# RCON 发送任意控制台命令到 NeoForge 开发服务端
# Send an arbitrary console command to the NeoForge dev server via RCON.
#
# 用法 / Usage:
#   powershell -ExecutionPolicy Bypass -File rcon-send.ps1 -Cmd "op Player1"
#   默认从本仓库 runs/server/server.properties 读取 rcon.port / rcon.password。

param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Cmd,
    [string]$Server = "127.0.0.1",
    [int]$Port = 0,
    [string]$Password = ""
)

$ErrorActionPreference = 'Stop'

$propsFile = Join-Path $PSScriptRoot "runs\server\server.properties"
if ((Test-Path $propsFile) -and ($Port -eq 0 -or [string]::IsNullOrEmpty($Password))) {
    foreach ($line in Get-Content $propsFile) {
        if ($line -match '^\s*rcon\.port\s*=\s*(\d+)\s*$')  { if ($Port -eq 0) { $Port = [int]$Matches[1] } }
        if ($line -match '^\s*rcon\.password\s*=\s*(.+?)\s*$') { if ([string]::IsNullOrEmpty($Password)) { $Password = $Matches[1] } }
    }
}
if ($Port -le 0)   { Write-Error "RCON port not found and -Port not given."; exit 1 }
if ([string]::IsNullOrEmpty($Password)) { Write-Error "RCON password not found and -Password not given."; exit 1 }

$client = New-Object System.Net.Sockets.TcpClient
try {
    $client.Connect($Server, $Port)
} catch {
    Write-Error ("RCON connect failed: " + $_.Exception.Message)
    exit 1
}
$stream = $client.GetStream()
$buf = New-Object byte[] 65536

function Send-Rcon {
    param([int]$Id, [int]$Type, [string]$Payload)
    $p = [System.Text.Encoding]::ASCII.GetBytes($Payload)
    $len = [BitConverter]::GetBytes([int](4 + 4 + $p.Length + 2))
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
    $login = Send-Rcon -Id 1 -Type 3 -Payload $Password
    if ($login.id -ne 1) {
        Write-Error "RCON auth failed."
        exit 1
    }
    $r = Send-Rcon -Id 2 -Type 2 -Payload $Cmd
    Write-Output ("[" + $Cmd + "] -> " + $r.payload)
} finally {
    $client.Close()
}
