# ============================================================================
#  极简 OTLP/HTTP 接收器 —— 用来验证「AgentScope 到底有没有真的在发 span」
#
#  它冒充 Langfuse 的 OTLP 入口（默认 127.0.0.1:4318/api/public/otel/v1/traces），
#  把收到的请求记到日志里：路径、Content-Type、字节数、有没有 Authorization 头。
#
#  【什么时候用它】
#  排查"Langfuse 里看不到 trace"时，先分清楚故障在哪一段：
#    1) 应用侧根本没产生 span      → 本接收器收不到任何请求
#    2) 应用侧在发、但 Langfuse 收不到 → 本接收器能收到，说明是端点/Key/网络问题
#  这样就不会把"代码问题"和"密钥问题"混在一起查（本项目实测踩过：
#  缺 opentelemetry-reactor-3.1 时表现为第一次请求 500，很像"Key 不对"）。
#
#  【用法】
#    # 1) 另开一个窗口先起接收器
#    powershell -NoProfile -ExecutionPolicy Bypass -File tools\otlp-sink.ps1
#
#    # 2) 把 commons\src\main\resources\.env 临时改成指向它
#    LANGFUSE_OTLP_ENDPOINT=http://127.0.0.1:4318/api/public/otel/v1/traces
#    LANGFUSE_PUBLIC_KEY=pk-lf-smoke-test
#    LANGFUSE_SECRET_KEY=sk-lf-smoke-test
#
#    # 3) 重新打包 + 重启 + 跑一次 POST /app（.env 会被打进 jar，必须重新打包）
#    .\run-all.ps1
#
#    # 4) 看 tools\otlp-sink\log.txt：bytes 明显大于 0 就说明 span 真的发出去了
#    # 5) 验完记得把 .env 改回 https://cloud.langfuse.com/... 并重新打包
#
#  实现说明：用 TcpListener 而不是 HttpListener —— HttpListener 监听非特权端口
#  需要 URL ACL 或管理员权限，TcpListener 绑 127.0.0.1 不需要。
#  只统计不解析 protobuf（我们只需要证明 span 被导出了）。
# ============================================================================

param(
    [int]$Port = 4318,
    [string]$LogFile
)

$ErrorActionPreference = "Stop"

if (-not $LogFile) {
    $LogFile = Join-Path $PSScriptRoot "otlp-sink.log.txt"
}
New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null

"=== OTLP sink 启动 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') 监听 127.0.0.1:$Port ===" |
    Out-File $LogFile -Encoding utf8

$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Parse("127.0.0.1"), $Port)
$listener.Start()
Write-Host "OTLP sink 已监听 http://127.0.0.1:$Port  （日志：$LogFile）" -ForegroundColor Green

while ($true) {
    $client = $listener.AcceptTcpClient()
    try {
        $stream = $client.GetStream()
        $stream.ReadTimeout = 5000

        # ---- 读 HTTP 头（读到 \r\n\r\n 为止）----
        $headerBytes = New-Object System.Collections.Generic.List[byte]
        $one = New-Object byte[] 1
        $state = 0   # 匹配 \r\n\r\n 的状态机
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

        $headerText = [System.Text.Encoding]::ASCII.GetString($headerBytes.ToArray())
        $firstLine = ($headerText -split "`r`n")[0]

        $contentLength = 0
        $contentType = ""
        $auth = ""
        foreach ($line in ($headerText -split "`r`n")) {
            if ($line -match '^(?i)Content-Length:\s*(\d+)') { $contentLength = [int]$Matches[1] }
            if ($line -match '^(?i)Content-Type:\s*(.+)$') { $contentType = $Matches[1].Trim() }
            if ($line -match '^(?i)Authorization:\s*(.+)$') { $auth = $Matches[1].Trim() }
        }

        # ---- 读 body ----
        $bodyLength = 0
        if ($contentLength -gt 0) {
            $buf = New-Object byte[] $contentLength
            $read = 0
            while ($read -lt $contentLength) {
                $n = $stream.Read($buf, $read, $contentLength - $read)
                if ($n -le 0) { break }
                $read += $n
            }
            $bodyLength = $read
        }

        # bytes > 0 就说明 AgentScope 真的产生并序列化了 span
        $authNote = if ($auth) { "有($($auth.Length) 字符)" } else { "无" }
        $line = "$(Get-Date -Format 'HH:mm:ss.fff') | $firstLine | ct=$contentType | bytes=$bodyLength | auth=$authNote"
        $line | Out-File $LogFile -Append -Encoding utf8
        Write-Host $line -ForegroundColor Cyan

        # ---- 回 200（OTLP 期望空 protobuf body）----
        $resp = "HTTP/1.1 200 OK`r`nContent-Type: application/x-protobuf`r`nContent-Length: 0`r`nConnection: close`r`n`r`n"
        $respBytes = [System.Text.Encoding]::ASCII.GetBytes($resp)
        $stream.Write($respBytes, 0, $respBytes.Length)
        $stream.Flush()
    } catch {
        "$(Get-Date -Format 'HH:mm:ss.fff') | 处理异常: $($_.Exception.Message)" | Out-File $LogFile -Append -Encoding utf8
    } finally {
        $client.Close()
    }
}
