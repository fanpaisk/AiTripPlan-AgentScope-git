# ============================================================================
#  EXP-001 对照实验跑批脚本 —— 单 Agent vs 多 Agent
#
#  【为什么用脚本而不是手工点 Apifox】
#    硬约束 1「接口测试一律用 Apifox」的意图是保持集合可复用、不要给 curl；
#    Apifox 集合同步加了 mode 字段作为人工验收入口。但本实验要跑几十次请求，
#    手工点击必然引入不一致（漏记、错序、超时设置不同），且无法自动做门禁与采集。
#    所以：Apifox 做人工验收，本脚本做批量执行 —— 见 docs/experiments/EXP-001/design.md §9。
#
#  【它做四件事】
#    1) 每次运行前门禁：探百度 MCP 地址（D1）+ 探 /api/health，不通过就停（不让坏数据进组）
#    2) 逐请求切臂（body 的 mode 字段），并校验响应回显的 mode 一致（防串臂）
#    3) 采集：runId / status / 耗时 / 回答长度 / 并行度 / 步数 → CSV
#    4) 用 Langfuse v2 Metrics API，把【时间窗口】内的 token 账目归属到这一次运行
#       （一次请求会产生多条 trace，A2A 不传 trace 上下文，所以无法用 traceId 聚合）
#
#  【用法】
#     # 冒烟轮（每臂各 1 次，只跑 P1）
#     powershell -File tools\experiment-run.ps1 -Arm multi,single -Prompts P1 -Reps 1
#     # 正式跑批
#     powershell -File tools\experiment-run.ps1 -Arm multi,single -Prompts P1,P3,P4 -Reps 2
#
#  【断点续跑】已存在的结果文件会跳过，除非加 -Force
# ============================================================================
param(
    [ValidateSet('multi', 'single')]
    [string[]]$Arm = @('multi', 'single'),

    # 上下文预算（EXP-002 机制 B）：on = 启用工具结果外置；off = 基线
    [ValidateSet('on', 'off')]
    [string[]]$Budget = @('on'),

    [string[]]$Prompts = @('P1', 'P3', 'P4'),

    [int]$Reps = 2,

    # 两次运行之间的间隔：给 span 刷出与摄取留时间，并避免 Langfuse 窗口互相污染
    [int]$GapSeconds = 20,

    # 运行结束后等多久再查 Langfuse（BatchSpanProcessor 约 5s 一批）
    # ★ 实测教训：25 秒【不够】。子 Agent 是独立 JVM，跨服务的 span 摄取更慢，
    #   冒烟轮在 25 秒时读到 multi 臂 176,298 tokens，沉淀后真实值是 401,962 —— 低估 2.3 倍。
    #   所以下面的默认值放大到 90 秒，并且【权威值一律以 -Recollect 事后重采为准】。
    [int]$IngestWaitSeconds = 90,

    [string]$OutDir,

    [switch]$SkipGate,
    [switch]$Force,

    # 只重采不跑请求：把所有已存在的 raw/*.json 按记录的起止时间重查一遍 token，
    # 并重建 results.csv。因为摄取有延迟，只有这个模式得到的数才是权威值。
    [switch]$Recollect
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
if (-not $OutDir) { $OutDir = Join-Path $Root 'docs\experiments\EXP-001' }
$RawDir = Join-Path $OutDir 'raw'
New-Item -ItemType Directory -Force -Path $RawDir | Out-Null
$CsvPath = Join-Path $OutDir 'results.csv'

# ---------------------------------------------------------------- 用户侧 prompt
# ★ 逐字固定，与 docs/experiments/EXP-001/prompts.md 保持一致；改动即改变实验条件
$PromptSet = [ordered]@{
    'P1' = '帮我规划深圳到惠州3日游，涵盖路线和行程'
    'P2' = '帮我规划深圳到惠州的自驾路线，给出总里程、预计耗时、主要高速和途经点'
    'P3' = '帮我规划一份杭州3天的家庭亲子游行程，包含景点、住宿、美食和预算'
    'P4' = '帮我安排深圳一日游'
    'P5' = '帮我安排个周末出行'
}

$ManagerBase = 'http://127.0.0.1:8081'

# ---------------------------------------------------------------- 读取 .env（不回显密钥）
function Get-DotEnv {
    $map = @{}
    $p = Join-Path $Root 'commons\src\main\resources\.env'
    if (-not (Test-Path $p)) { return $map }
    Get-Content $p -Encoding utf8 | ForEach-Object {
        $l = $_.Trim()
        if ($l -and -not $l.StartsWith('#') -and $l.Contains('=')) {
            $i = $l.IndexOf('=')
            $map[$l.Substring(0, $i).Trim()] = $l.Substring($i + 1).Trim()
        }
    }
    return $map
}
$EnvMap = Get-DotEnv
$LangfuseAuth = $null
if ($EnvMap['LANGFUSE_PUBLIC_KEY'] -and $EnvMap['LANGFUSE_SECRET_KEY']) {
    $LangfuseAuth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
            ($EnvMap['LANGFUSE_PUBLIC_KEY'] + ':' + $EnvMap['LANGFUSE_SECRET_KEY'])))
}

function Get-HttpBody($url, $timeoutSec = 30) {
    $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $timeoutSec
    return [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
}

# ---------------------------------------------------------------- 门禁
function Test-Gate {
    if ($SkipGate) { return $true }

    # 1) 百度地图 MCP 地址（D1：失效时路线能力退化，两组就不可比）
    $mcp = $EnvMap['BAIDU_MAP_MCP_SSE_URL']
    if (-not $mcp) {
        Write-Host '      [门禁] .env 里没有 BAIDU_MAP_MCP_SSE_URL' -ForegroundColor Red
        return $false
    }
    # ★ 超时别设太短：这是 SSE 端点，TLS+WAF 握手可能要数秒。
    #   踩过的坑：设 --max-time 8 时偶发拿到 000（握手未完成就被中断），把好地址误判成失效、
    #   整批被门禁拦下。而同一地址用 10/25 秒探都是 200。
    #   注意 curl 读 SSE 会一直挂着直到 --max-time，所以每轮门禁约耗 20 秒，属正常现象。
    $code = '000'
    foreach ($attempt in 1..2) {
        $code = & curl.exe -s -o NUL -w '%{http_code}' --connect-timeout 5 --max-time 20 $mcp 2>$null
        if ("$code" -eq '200') { break }
        if ($attempt -lt 2) { Start-Sleep -Seconds 2 }
    }
    if ("$code" -ne '200') {
        Write-Host "      [门禁] 百度 MCP 地址返回 $code（重试后仍非 200）—— 按 D1 处理：暂停，不要让坏数据进组" -ForegroundColor Red
        return $false
    }

    # 2) 主管服务健康
    try {
        $h = (Get-HttpBody "$ManagerBase/api/health" 15) | ConvertFrom-Json
        if ($h.status -ne 'UP' -or $h.registeredAgents -lt 2) {
            Write-Host "      [门禁] /api/health 异常：$($h | ConvertTo-Json -Compress)" -ForegroundColor Red
            return $false
        }
    } catch {
        Write-Host "      [门禁] /api/health 不可达：$($_.Exception.Message)" -ForegroundColor Red
        return $false
    }
    return $true
}

# ---------------------------------------------------------------- Langfuse 采集
function Get-TokenWindow($fromUtc, $toUtc) {
    if (-not $LangfuseAuth) { return $null }
    $q = @{
        view          = 'observations'
        metrics       = @(
            @{ measure = 'totalTokens'; aggregation = 'sum' },
            @{ measure = 'inputTokens'; aggregation = 'sum' },
            @{ measure = 'outputTokens'; aggregation = 'sum' },
            @{ measure = 'count'; aggregation = 'count' }
        )
        dimensions    = @()
        filters       = @()
        fromTimestamp = $fromUtc
        toTimestamp   = $toUtc
    } | ConvertTo-Json -Depth 8 -Compress
    $url = 'https://cloud.langfuse.com/api/public/v2/metrics?query=' + [Uri]::EscapeDataString($q)
    # 带重试：实测遇到过 DNS 偶发解析失败（The remote name could not be resolved），
    # 一次失败就让整轮的 token 缺口，会让整组数据不完整。
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            $r = Invoke-WebRequest -Uri $url -Headers @{ Authorization = $LangfuseAuth } -UseBasicParsing -TimeoutSec 60
            $d = ($r.Content | ConvertFrom-Json).data[0]
            return [pscustomobject]@{
                totalTokens  = [int]($d.sum_totalTokens)
                inputTokens  = [int]($d.sum_inputTokens)
                outputTokens = [int]($d.sum_outputTokens)
                observations = [int]($d.count_count)
            }
        } catch {
            if ($attempt -lt 3) {
                Write-Host "      [Langfuse] 第 $attempt 次查询失败，$($attempt * 3)s 后重试：$($_.Exception.Message)" -ForegroundColor DarkYellow
                Start-Sleep -Seconds ($attempt * 3)
            } else {
                Write-Host "      [Langfuse] 查询最终失败：$($_.Exception.Message)" -ForegroundColor Yellow
            }
        }
    }
    return $null
}

# ---------------------------------------------------------------- 单次运行
function Invoke-ExperimentRun($promptId, $mode, $budget, $rep) {
    $prompt = $PromptSet[$promptId]

    # ★ PowerShell 的坑：数组对 -eq 是「过滤」语义、返回的仍是数组，
    #   直接塞进 ConvertTo-Json 会变成 []，服务端 Jackson 报
    #   "Cannot deserialize Boolean from Array value" → HTTP 400（且不烧 token，很难发现）。
    #   所以这里显式取首元素 + 显式转成标量布尔。
    $modeName = [string]@($mode)[0]
    $budgetName = [string]@($budget)[0]
    $budgetFlag = [bool]($budgetName -eq 'on')

    $fileBase = "{0}_b{1}_{2}_r{3}" -f $modeName, $budgetName, $promptId, $rep
    $jsonPath = Join-Path $RawDir "$fileBase.json"

    if ((Test-Path $jsonPath) -and -not $Force) {
        Write-Host "      [跳过] $fileBase 已存在（-Force 可覆盖）" -ForegroundColor DarkGray
        return $null
    }

    $body = @{ prompt = $prompt; mode = $modeName; contextBudget = $budgetFlag } | ConvertTo-Json -Compress
    $startUtc = [DateTime]::UtcNow
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $record = [ordered]@{
        arm = $modeName; budget = $budgetName; promptId = $promptId; rep = $rep; prompt = $prompt
        requestBody = $body
        startUtc = $startUtc.ToString('o'); httpStatus = $null; status = $null; error = $null
        runId = $null; modeEcho = $null; durationMillis = $null; wallSeconds = $null
        answerChars = $null; parallelism = $null; stepCount = $null
        mapToolsMounted = $null
        tokenTotal = $null; tokenInput = $null; tokenOutput = $null; observations = $null
    }
    try {
        $r = Invoke-WebRequest -Uri "$ManagerBase/app" -Method Post `
            -ContentType 'application/json; charset=utf-8' `
            -Body ([Text.Encoding]::UTF8.GetBytes($body)) -UseBasicParsing -TimeoutSec 900
        $sw.Stop()
        $resp = ([Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())) | ConvertFrom-Json
        $record.httpStatus = [int]$r.StatusCode
        $record.status = $resp.status
        $record.error = $resp.error
        $record.runId = $resp.runId
        $record.modeEcho = $resp.mode
        $record.durationMillis = $resp.durationMillis
        $record.wallSeconds = [int]$sw.Elapsed.TotalSeconds
        if ($resp.answer) { $record.answerChars = $resp.answer.Length }
        $record.parallelism = $resp.parallelismSummary
        if ($resp.steps) { $record.stepCount = @($resp.steps).Count }
        $record.answer = $resp.answer

        if ($resp.mode -ne $modeName) {
            Write-Host "      ⚠ 臂码不符：请求 $modeName，响应回显 $($resp.mode) —— 该轮标记为可疑" -ForegroundColor Red
            $record.error = "MODE_MISMATCH: requested=$modeName echoed=$($resp.mode)"
        }
        if ($null -ne $resp.contextBudget -and [bool]$resp.contextBudget -ne $budgetFlag) {
            Write-Host "      ⚠ 预算档不符：请求 budget=$budget，响应回显 $($resp.contextBudget) —— 该轮标记为可疑" -ForegroundColor Red
            $record.error = "BUDGET_MISMATCH: requested=$budget echoed=$($resp.contextBudget)"
        }
    } catch {
        $sw.Stop()
        $record.error = $_.Exception.Message
        $record.wallSeconds = [int]$sw.Elapsed.TotalSeconds
        $record.status = 'TRANSPORT_FAILED'
        # ★ 必须显式打印：静默吞掉传输错误会让整轮变成"没有数据"，与"数据为 0"难以区分
        #   （同类教训见 DECISIONS D-022）
        Write-Host "      ✗ 请求失败（$([int]$sw.Elapsed.TotalSeconds)s）：$($_.Exception.Message)" -ForegroundColor Red
    }
    $endUtc = [DateTime]::UtcNow

    # 地图工具挂载状态（D1 证据）：每轮都记，便于事后剔除异常轮次
    try {
        $m = Select-String -Path (Join-Path $Root 'logs\routeMaking_agent.out.log') -Pattern '已挂载的工具' -Encoding utf8 |
            Select-Object -Last 1
        if ($m) { $record.mapToolsMounted = ([regex]::Matches($m.Line, 'map_')).Count }
    } catch { }

    # token 归属：用本次运行的时间窗口（前后各留 3 秒缓冲）
    if ($record.status -eq 'SUCCESS' -or $record.status -eq 'FAILED') {
        Write-Host "      等待 $IngestWaitSeconds s 以供 span 摄取..." -ForegroundColor DarkGray
        Start-Sleep -Seconds $IngestWaitSeconds
        $tok = Get-TokenWindow $startUtc.AddSeconds(-3).ToString('yyyy-MM-ddTHH:mm:ssZ') $endUtc.AddSeconds(3).ToString('yyyy-MM-ddTHH:mm:ssZ')
        if ($tok) {
            $record.tokenTotal = $tok.totalTokens
            $record.tokenInput = $tok.inputTokens
            $record.tokenOutput = $tok.outputTokens
            $record.observations = $tok.observations
        }
    }

    # 用 .NET 直接写：不依赖 Set-Content 的参数集，且明确不带 BOM
    [IO.File]::WriteAllText($jsonPath, ($record | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
    Write-Host ("      ✔ {0,-4} {1,-3} b{2,-3} r{3}  status={4}  {5}s  {6}字  tokens={7}  obs={8}" -f `
            $modeName, $promptId, $budgetName, $rep, $record.status, $record.wallSeconds, $record.answerChars,
        $record.tokenTotal, $record.observations) -ForegroundColor Green
    return $record
}

# ---------------------------------------------------------------- 主流程
Write-Host '=== EXP-001 跑批 ===' -ForegroundColor Cyan
Write-Host "  臂        : $($Arm -join ', ')"
Write-Host "  prompt 集 : $($Prompts -join ', ')"
Write-Host "  重复次数  : $Reps"
Write-Host "  结果目录  : $OutDir"
Write-Host ''
# ---------------------------------------------------------------- 重采模式
# ★ 权威数据来源：跑完所有轮次并沉淀后再执行一次 -Recollect。
#   逐轮即时查询只是"临时值"，供跑批中途观察用。
if ($Recollect) {
    Write-Host '=== 重新采集（沉淀后重查所有窗口）===' -ForegroundColor Cyan
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($f in (Get-ChildItem -Path $RawDir -Filter '*.json' | Sort-Object Name)) {
        # ★ 用 -LiteralPath：PowerShell 把路径里的 [ ] 当通配符，文件名一旦含方括号
        #   （曾经因为 bug 写出过 single_bSystem.String[]_P1_r1.json）就会让参数绑定失败、
        #   整个重采循环被中断。读文件一律用 -LiteralPath。
        $raw = $null
        try {
            $raw = Get-Content -LiteralPath $f.FullName -Raw -Encoding utf8
            $r = $raw | ConvertFrom-Json
        } catch {
            Write-Host ("  {0,-28} 解析失败，已跳过：{1}" -f $f.Name, $_.Exception.Message) -ForegroundColor Yellow
            continue
        }
        if (-not $r.startUtc) { continue }
        $st = ([DateTime]::Parse($r.startUtc)).ToUniversalTime()
        $from = $st.AddSeconds(-3).ToString('yyyy-MM-ddTHH:mm:ssZ')
        $to = $st.AddSeconds([int]$r.wallSeconds + 5).ToString('yyyy-MM-ddTHH:mm:ssZ')
        $tok = Get-TokenWindow $from $to
        if ($tok) {
            $r.tokenTotal = $tok.totalTokens
            $r.tokenInput = $tok.inputTokens
            $r.tokenOutput = $tok.outputTokens
            $r.observations = $tok.observations
            $r | ConvertTo-Json -Depth 6 | ForEach-Object { [IO.File]::WriteAllText($f.FullName, $_, (New-Object Text.UTF8Encoding($false))) }
            Write-Host ("  {0,-24} {1,9} tokens  ({2} obs)" -f $f.Name, $tok.totalTokens, $tok.observations) -ForegroundColor Green
        } else {
            Write-Host ("  {0,-24} 查询失败，保留原值" -f $f.Name) -ForegroundColor Yellow
        }
        $rows.Add([pscustomobject]($r | Select-Object * -ExcludeProperty answer))
    }
    if ($rows.Count -gt 0) {
        $rows | Export-Csv -Path $CsvPath -NoTypeInformation -Encoding UTF8
        Write-Host ''
        Write-Host '=== 按臂汇总（权威值）===' -ForegroundColor Cyan
        $rows | Group-Object { "$($_.arm)/b$($_.budget)" } | ForEach-Object {
            $g = $_.Group
            $tk = ($g | Measure-Object tokenTotal -Sum).Sum
            $ms = ($g | Measure-Object durationMillis -Average).Average
            Write-Host ("  {0,-7} {1} 次，token 合计 {2}，单次均值 {3}，耗时均值 {4}s" -f `
                    $_.Name, $g.Count, $tk, [int]($tk / $g.Count), [int]($ms / 1000))
        }
    }
    Write-Host "  CSV: $CsvPath"
    exit 0
}

if (-not (Test-Gate)) {
    Write-Host '门禁未通过，已中止。请先修好依赖再跑 —— 让坏数据进组会让整组结论作废。' -ForegroundColor Red
    exit 1
}
Write-Host '  门禁通过（百度 MCP 可达 + 主管健康）' -ForegroundColor Green
Write-Host ''

$all = New-Object System.Collections.Generic.List[object]

# ★ 交错执行：按 (prompt × rep) 外层循环、臂与预算档在内层，避免"先跑完一组再跑另一组"引入时间漂移
for ($rep = 1; $rep -le $Reps; $rep++) {
    foreach ($pid_ in $Prompts) {
        foreach ($mode in $Arm) {
            foreach ($budget in $Budget) {
                Write-Host "  --- $pid_ r$rep / $mode / budget=$budget ---" -ForegroundColor Cyan
                if (-not (Test-Gate)) {
                    Write-Host '  门禁在运行途中失败 —— 已暂停。请处理后重跑（已完成的轮次会自动跳过）。' -ForegroundColor Red
                    $all | Export-Csv -Path $CsvPath -NoTypeInformation -Encoding UTF8
                    exit 2
                }
                $rec = Invoke-ExperimentRun $pid_ $mode $budget $rep
                if ($rec) { $all.Add([pscustomobject]$rec) }
                Start-Sleep -Seconds $GapSeconds
            }
        }
    }
}

# 汇总：把本轮新增的行合并进 CSV
if ($all.Count -gt 0) {
    $rows = $all | ForEach-Object { $_ | Select-Object * -ExcludeProperty answer }
    if (Test-Path $CsvPath) {
        $rows | Export-Csv -Path $CsvPath -NoTypeInformation -Encoding UTF8 -Append
    } else {
        $rows | Export-Csv -Path $CsvPath -NoTypeInformation -Encoding UTF8
    }
}

Write-Host ''
Write-Host '=== 完成 ===' -ForegroundColor Cyan
Write-Host "  原始结果：$RawDir"
Write-Host "  汇总 CSV：$CsvPath"
if ($all.Count -gt 0) {
    Write-Host ''
    $all | Group-Object { "$($_.arm)/b$($_.budget)" } | ForEach-Object {
        $g = $_.Group
        $tk = ($g | Measure-Object tokenTotal -Sum).Sum
        $ms = ($g | Measure-Object durationMillis -Average).Average
        Write-Host ("  {0,-14} 运行 {1} 次，token 合计 {2}，单次均值 {3}，耗时均值 {4}s" -f `
                $_.Name, $g.Count, $tk, [int]($tk / $g.Count), [int]($ms / 1000))
    }
}
