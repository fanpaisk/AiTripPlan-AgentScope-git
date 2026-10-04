# ============================================================================
#  AiTripPlan 停止脚本（Windows PowerShell）
#  用法：.\stop-all.ps1
# ============================================================================

$Ports = @(8081, 8082, 8085)

Write-Host "=== 停止 AiTripPlan 服务 ===" -ForegroundColor Cyan

foreach ($port in $Ports) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if (-not $conns) {
        Write-Host "      端口 $port 没有监听进程" -ForegroundColor DarkGray
        continue
    }
    foreach ($conn in $conns) {
        $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
        if ($proc) {
            Write-Host "      停止端口 $port 上的进程 $($proc.Id) ($($proc.ProcessName))" -ForegroundColor Yellow
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

Write-Host "=== 完成 ===" -ForegroundColor Cyan
