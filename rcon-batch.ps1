# Batch RCON: open one connection, run a list of commands, print outputs.
# Usage: powershell -File rcon-batch.ps1 -Cmds "c1;c2;c3"   (semicolon-separated)
param(
    [Parameter(Mandatory = $true)]
    [string]$Cmds
)
$ErrorActionPreference = 'Stop'

$propsFile = Join-Path $PSScriptRoot "runs\server\server.properties"
$Port = 0; $Password = ""
foreach ($line in Get-Content $propsFile) {
    if ($line -match '^\s*rcon\.port\s*=\s*(\d+)\s*$')  { $Port = [int]$Matches[1] }
    if ($line -match '^\s*rcon\.password\s*=\s*(.+?)\s*$') { $Password = $Matches[1] }
}
if ($Port -le 0 -or [string]::IsNullOrEmpty($Password)) { Write-Error "RCON config missing"; exit 1 }

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect("127.0.0.1", $Port)
$stream = $client.GetStream()
$buf = New-Object byte[] 262144

function Send-Rcon {
    param([int]$Id, [int]$Type, [string]$Payload)
    $p = [System.Text.Encoding]::ASCII.GetBytes($Payload)
    $len = [BitConverter]::GetBytes([int](4 + 4 + $p.Length + 2))
    $packet = New-Object byte[] (12 + $p.Length + 2)
    [Array]::Copy($len, 0, $packet, 0, 4)
    [Array]::Copy([BitConverter]::GetBytes([int]$Id), 0, $packet, 4, 4)
    [Array]::Copy([BitConverter]::GetBytes([int]$Type), 0, $packet, 8, 4)
    [Array]::Copy($p, 0, $packet, 12, $p.Length)
    $stream.Write($packet, 0, $packet.Length); $stream.Flush()
    $n = $stream.Read($buf, 0, $buf.Length)
    if ($n -lt 12) { throw "Short RCON response" }
    $respLen = [BitConverter]::ToInt32($buf, 0)
    while ($n -lt $respLen + 4) {
        $more = $stream.Read($buf, $n, $buf.Length - $n)
        if ($more -le 0) { break }
        $n += $more
    }
    $respId = [BitConverter]::ToInt32($buf, 4)
    $payloadLen = [Math]::Max(0, $n - 12)
    return [System.Text.Encoding]::ASCII.GetString($buf, 12, $payloadLen).TrimEnd([char]0)
}

$login = Send-Rcon -Id 1 -Type 3 -Payload $Password
if ($login -ne $Password -and $login.Length -gt 0 -and $login[0] -ne [char]2) { Write-Error "auth failed: $login"; exit 1 }

$id = 10
foreach ($cmd in $Cmds.Split(';')) {
    if ([string]::IsNullOrWhiteSpace($cmd)) { continue }
    $id++
    $r = Send-Rcon -Id $id -Type 2 -Payload $cmd
    Write-Output ("> " + $cmd)
    Write-Output $r
}
$client.Close()
