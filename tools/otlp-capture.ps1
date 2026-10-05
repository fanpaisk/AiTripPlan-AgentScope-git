# ============================================================================
#  OTLP/HTTP 采集器 —— 与 tools\otlp-sink.ps1 同构，但把每个请求的 body 落盘
#
#  【和 otlp-sink.ps1 的分工】
#    otlp-sink.ps1    ：只统计「字节数 / Content-Type / 有无 Authorization」，
#                       用来回答「span 到底发出去了没有」。
#    otlp-capture.ps1 ：把报文存成 dump-NNN.bin，用来回答
#                       「span 上到底带了哪些属性」——protobuf 的字段名是
#                       明文 ASCII，直接 grep 就能判读，不需要写解析器。
#
#  【为什么需要它】
#    Langfuse 里看不到 token 账目时，必须先分清故障在哪一段：
#      a) span 上根本没有 gen_ai.usage.*        → 应用侧问题
#      b) span 上有、但查询姿势不对             → 见 DECISIONS.md D-018
#    只看 Langfuse 的界面/接口无法区分 a 和 b，把报文抓下来才能定论。
#
#  【用法】（关键：用环境变量覆盖，不要改 .env —— 省掉重打包与恢复步骤，见 D-020）
#     # 1) 起采集器
#     powershell -NoProfile -ExecutionPolicy Bypass -File tools\otlp-capture.ps1 -Port 4319
#     # 2) 只重启目标服务，并在启动它的那个会话里设好环境变量
#     $env:LANGFUSE_OTLP_ENDPOINT = 'http://127.0.0.1:4319/api/public/otel/v1/traces'
#     # 3) 触发一次请求，然后看 $env:TEMP\otlp-dump\dump-*.bin
#
#  【判读提示】protobuf 里 KeyValue 的结构是 key(字段1) + value(字段2)，
#    AnyValue 的类型由首字节决定：0x0A=string、0x18=int、0x21=double。
#    所以「键名之后的字节」能直接看出值的类型对不对（int 才是正确的 token 类型）。
# ============================================================================
param(
    [int]$Port = 4319,
    [string]$OutDir
)
$ErrorActionPreference = 'Stop'
if (-not $OutDir) { $OutDir = Join-Path $env:TEMP 'otlp-dump' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Get-ChildItem -Path $OutDir -Filter 'dump-*.bin' -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Parse('127.0.0.1'), $Port)
$listener.Start()
Write-Host "capture sink listening on 127.0.0.1:$Port -> $OutDir" -ForegroundColor Green

$i = 0
while ($true) {
    $client = $listener.AcceptTcpClient()
    try {
        $stream = $client.GetStream()
        $stream.ReadTimeout = 5000

        # ---- 读 HTTP 头（读到 \r\n\r\n 为止）----
        $headerBytes = New-Object System.Collections.Generic.List[byte]
        $one = New-Object byte[] 1
        $state = 0
        while ($true) {
            $n = $stream.Read($one, 0, 1)
            if ($n -le 0) { break }
            $b = $one[0]
            $headerBytes.Add($b)
            switch ($state) {
                0 { if ($b -eq 13) { $state = 1 } }
                1 { if ($b -eq 10) { $state = 2 } else { $state = 0 } }
                2 { if ($b -eq 13) { $state = 3 } else { $state = 0 } }
                3 { if ($b -eq 10) { $state = 4 } else { $state = 0 } }
            }
            if ($state -eq 4) { break }
            if ($headerBytes.Count -gt 65536) { break }
        }
        $headerText = [Text.Encoding]::ASCII.GetString($headerBytes.ToArray())

        $contentLength = 0
        foreach ($line in ($headerText -split "`r`n")) {
            if ($line -match '^(?i)Content-Length:\s*(\d+)') { $contentLength = [int]$Matches[1] }
        }

        # ---- 读 body 并落盘 ----
        $body = New-Object byte[] 0
        if ($contentLength -gt 0) {
            $buf = New-Object byte[] $contentLength
            $read = 0
            while ($read -lt $contentLength) {
                $n = $stream.Read($buf, $read, $contentLength - $read)
                if ($n -le 0) { break }
                $read += $n
            }
            if ($read -gt 0) { $body = $buf[0..($read - 1)] }
        }

        $i++
        $f = Join-Path $OutDir ("dump-{0:D3}.bin" -f $i)
        [IO.File]::WriteAllBytes($f, $body)
        Write-Host "saved $f ($($body.Length) bytes)" -ForegroundColor Cyan

        # ---- 回 200（OTLP 期望空 protobuf body）----
        $resp = "HTTP/1.1 200 OK`r`nContent-Type: application/x-protobuf`r`nContent-Length: 0`r`nConnection: close`r`n`r`n"
        $rb = [Text.Encoding]::ASCII.GetBytes($resp)
        $stream.Write($rb, 0, $rb.Length)
        $stream.Flush()
    } catch {
        Write-Host "handle error: $($_.Exception.Message)"
    } finally {
        $client.Close()
    }
}
