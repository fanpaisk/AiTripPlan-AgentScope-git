# ============================================================================
#  AiTripPlan 一键启动脚本（Windows PowerShell）
#
#  用法（在 AiTripPlan-AgentScope 目录下执行）：
#     .\run-all.ps1                  # 打包 + 启动三个服务
#     .\run-all.ps1 -SkipBuild       # 跳过打包，直接启动（改配置后重启很方便）
#     .\run-all.ps1 -Only manager    # 只启动主管 Agent
#     .\run-all.ps1 -HeapMb 768      # 调大单个 JVM 堆上限
#
#  前置条件：
#     1. Nacos 3.x 已在 NACOS_SERVER_ADDR 指定的地址运行
#     2. commons/src/main/resources/.env 里填好了 LLM_API_KEY（或 ALIBABA_DASHCOPE_KEY）
#
#  ★ 为什么要限制堆（-Xmx）：
#    不加 -Xmx 时 JVM 默认按物理内存的 1/4 取上限（16G 机器 ≈ 每个进程可涨到 4G）。
#    三个服务 + Nacos + Langfuse 同时跑，内存会被瞬间吃光（实测涨到 ~7G 后
#    Docker/Langfuse 被 OOM Killer 干掉，日志里只留下 Exited (137)）。
#    实测 384m 足够跑完整链路，这里默认 512m 留一点余量。
# ============================================================================

param(
    [switch]$SkipBuild,
    [ValidateSet("all", "manager", "route", "planner")]
    [string]$Only = "all",
    [int]$HeapMb = 512
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$LogDir = Join-Path $Root "logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# 三个服务：模块目录 -> jar 名 / 端口
$Services = @(
    @{ Key = "planner"; Module = "tripPlanner_agent";  Jar = "tripPlanner_agent-1.0-SNAPSHOT.jar";  Port = 8085; Name = "行程规划TripPlannerAgent" },
    @{ Key = "route";   Module = "routeMaking_agent";  Jar = "routeMaking_agent-1.0-SNAPSHOT.jar";  Port = 8082; Name = "路线制定RouteMakingAgent" },
    @{ Key = "manager"; Module = "manager_agent";      Jar = "manager_agent-1.0-SNAPSHOT.jar";      Port = 8081; Name = "主管ManagerAgent" }
)

Write-Host "=== AiTripPlan 启动器 ===" -ForegroundColor Cyan

# ---------- 1. 检查 Java ----------
# 优先用 JAVA_HOME 里的 java：PATH 里可能是一个版本不对的 JDK（本项目要求 17）。
$JavaExe = "java"
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path $candidate) { $JavaExe = $candidate }
} else {
    Write-Host "[警告] 未设置 JAVA_HOME，将使用 PATH 中的 java。建议设置 JAVA_HOME 指向 JDK 17。" -ForegroundColor Yellow
}

# ---------- 2. 检查密钥 ----------
$EnvFile = Join-Path $Root "commons\src\main\resources\.env"
if (Test-Path $EnvFile) {
    $content = Get-Content $EnvFile -Raw
    # 模型密钥现在叫 LLM_API_KEY（厂商中立），旧的 ALIBABA_DASHCOPE_KEY 仍可作兜底，
    # 所以两者【都】为空才算没配。
    $hasLlmKey = $content -match "(?m)^\s*LLM_API_KEY=\s*\S"
    $hasDsKey  = $content -match "(?m)^\s*ALIBABA_DASHCOPE_KEY=\s*\S"
    if (-not $hasLlmKey -and -not $hasDsKey) {
        Write-Host "[警告] .env 里 LLM_API_KEY 与 ALIBABA_DASHCOPE_KEY 都是空的，服务会因为读不到模型密钥而启动失败。" -ForegroundColor Yellow
        Write-Host "        请编辑：$EnvFile" -ForegroundColor Yellow
    }
} else {
    Write-Host "[警告] 没有找到 $EnvFile" -ForegroundColor Yellow
}

# ---------- 3. 打包 ----------
if (-not $SkipBuild) {
    Write-Host "`n[1/2] Maven 打包（跳过测试）..." -ForegroundColor Cyan
    # 如果同目录存在 build-settings.xml 就自动带上 -s：
    # 它用来把 Maven 本地仓库指到自定义位置（离线构建 / C 盘紧张时很有用）。
    # 该文件因人而异，已在 .gitignore 中忽略，所以这里用"存在才带"的方式处理。
    $settings = Join-Path $Root "build-settings.xml"
    $mvnArgs = @("-q", "clean", "package", "-DskipTests")
    if (Test-Path $settings) { $mvnArgs = @("-s", $settings) + $mvnArgs }
    Push-Location $Root
    & mvn @mvnArgs
    if ($LASTEXITCODE -ne 0) {
        Pop-Location
        Write-Host "[失败] Maven 打包失败，请看上面的错误输出。" -ForegroundColor Red
        exit 1
    }
    Pop-Location
    Write-Host "      打包完成。" -ForegroundColor Green
} else {
    Write-Host "`n[1/2] 跳过打包。" -ForegroundColor DarkGray
}

# ---------- 4. 启动 ----------
Write-Host "`n[2/2] 启动服务..." -ForegroundColor Cyan

$started = @()
foreach ($svc in $Services) {
    if ($Only -ne "all" -and $Only -ne $svc.Key) { continue }

    $jar = Join-Path $Root "$($svc.Module)\target\$($svc.Jar)"
    if (-not (Test-Path $jar)) {
        Write-Host "      [跳过] 找不到 $jar ，请先执行 .\run-all.ps1 （不要加 -SkipBuild）" -ForegroundColor Red
        continue
    }

    $outLog = Join-Path $LogDir "$($svc.Module).out.log"
    $errLog = Join-Path $LogDir "$($svc.Module).err.log"

    Start-Process -FilePath $JavaExe `
        -ArgumentList @(
            "-Dfile.encoding=UTF-8",
            "-Duser.timezone=Asia/Shanghai",
            "-Xmx${HeapMb}m",
            "-XX:+ExitOnOutOfMemoryError",
            "-jar", $jar
        ) `
        -WorkingDirectory (Join-Path $Root $svc.Module) `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden

    Write-Host "      [启动] $($svc.Name)  端口 $($svc.Port)  堆上限 ${HeapMb}m  日志 logs\$($svc.Module).out.log" -ForegroundColor Green
    $started += $svc
    Start-Sleep -Milliseconds 400
}

# ---------- 5. 等端口就绪 ----------
Write-Host "`n等待端口就绪..." -ForegroundColor Cyan
foreach ($svc in $started) {
    $ok = $false
    for ($i = 0; $i -lt 60; $i++) {
        try {
            $client = New-Object System.Net.Sockets.TcpClient
            $client.Connect("127.0.0.1", $svc.Port)
            $client.Close()
            $ok = $true
            break
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if ($ok) {
        Write-Host "      [就绪] $($svc.Name) -> http://127.0.0.1:$($svc.Port)" -ForegroundColor Green
    } else {
        Write-Host "      [超时] $($svc.Name) 端口 $($svc.Port) 未就绪，请看 logs\$($svc.Module).out.log" -ForegroundColor Red
    }
}

Write-Host "`n=== 完成 ===" -ForegroundColor Cyan
Write-Host "主管入口   POST http://127.0.0.1:8081/app          （同步）"
Write-Host "流式入口   POST http://127.0.0.1:8081/app/stream   （SSE）"
Write-Host "子Agent    GET  http://127.0.0.1:8081/api/agents"
Write-Host "运行轨迹   GET  http://127.0.0.1:8081/api/runs"
Write-Host "停止服务   .\stop-all.ps1"
