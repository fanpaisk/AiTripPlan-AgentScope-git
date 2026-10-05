# ============================================================================
#  EXP-001 盲评脚本 —— 对两臂的回答做 5 维打分
#
#  【为什么要脚本而不是我自己打分】
#    我知道哪份回答来自哪条臂，直接打分会带上确认偏差。本脚本：
#      1) 把同一题的两份回答随机指派为「方案甲 / 方案乙」，映射记进 judge-mapping.csv
#      2) 每对评 3 次，取中位数（降低评审噪声）
#      3) 评分完成后再拆盲，写进 score.csv
#    评审模型与被评 Agent 是同一个模型（DeepSeek）——这是已知局限，必须写进报告。
#
#  【用法】
#     powershell -File tools\experiment-judge.ps1
#     powershell -File tools\experiment-judge.ps1 -Repeats 5
#
#  【产出】
#     docs/experiments/EXP-001/judge-mapping.csv   盲评映射（评分后才有意义，先别打开看）
#     docs/experiments/EXP-001/score.csv           拆盲后的评分明细
# ============================================================================
param(
    [int]$Repeats = 3,
    [string]$OutDir,
    [string]$Model,
    # 只看这些 prompt（默认全部）
    [string[]]$Prompts = @()
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
if (-not $OutDir) { $OutDir = Join-Path $Root 'docs\experiments\EXP-001' }
$RawDir = Join-Path $OutDir 'raw'

# ---------------------------------------------------------------- 密钥（不回显）
$EnvMap = @{}
Get-Content (Join-Path $Root 'commons\src\main\resources\.env') -Encoding utf8 | ForEach-Object {
    $l = $_.Trim()
    if ($l -and -not $l.StartsWith('#') -and $l.Contains('=')) {
        $i = $l.IndexOf('=')
        $EnvMap[$l.Substring(0, $i).Trim()] = $l.Substring($i + 1).Trim()
    }
}
$ApiKey = $EnvMap['LLM_API_KEY']
if (-not $ApiKey) { throw '.env 里没有 LLM_API_KEY' }
$BaseUrl = if ($EnvMap['LLM_BASE_URL']) { $EnvMap['LLM_BASE_URL'].TrimEnd('/') } else { 'https://api.deepseek.com' }
if (-not $Model) { $Model = $EnvMap['LLM_MODEL'] }

# ---------------------------------------------------------------- 评分 rubric
$Rubric = @'
你是严格的行程方案评审。下面给你【同一个用户需求】的两份独立回答（方案甲、方案乙）。
请分别按 5 个维度打分，每维 1-5 分（5 最好）。评分依据必须只看回答本身，不要去猜哪份是谁写的。

维度定义：
1. facts  事实可用性：有无编造。编造里程/耗时/景点/价格/天气 = 低分；明确标注估算值 = 高分。
2. cover  需求覆盖度：是否覆盖 ①总体路线 ②每日行程 ③餐饮 ④住宿 ⑤天气与注意事项 ⑥预算 六部分。
3. action  可执行性：时间与路线是否自洽、能否照着走（含交通方式、顺序、耗时是否合理）。
4. struct 结构清晰度：Markdown 结构、表格使用、层次是否清楚。
5. honest 如实性：数据来源与失败/不确定之处是否如实说明；把估算当真实数据、或编造失败原因 = 低分。

只输出 JSON，不要任何解释文字，格式严格如下：
{"A":{"facts":0,"cover":0,"action":0,"struct":0,"honest":0},"B":{"facts":0,"cover":0,"action":0,"struct":0,"honest":0},"note":"一句话说明两者最大差别"}
'@

# ---------------------------------------------------------------- 收集样本
$runs = @()
Get-ChildItem -Path $RawDir -Filter '*.json' | ForEach-Object {
    $r = Get-Content -LiteralPath $_.FullName -Raw -Encoding utf8 | ConvertFrom-Json
    if ($r.status -eq 'SUCCESS' -and $r.answer -and (-not $Prompts -or $Prompts -contains $r.promptId)) {
        $runs += $r
    }
}
Write-Host "读到 $($runs.Count) 份可用回答（status=SUCCESS 且有正文）"

# 按 (promptId, rep) 配对
$pairs = $runs | Group-Object { "$($_.promptId)_r$($_.rep)" } | Where-Object { $_.Count -eq 2 }
Write-Host "可配对的题组：$($pairs.Count) 组（每组两臂各一份）"
if ($pairs.Count -eq 0) { Write-Host '没有可配对的样本，退出。' -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- 调用评审模型
function Invoke-Judge($userPrompt, $textA, $textB) {
    $content = @"
【用户需求】
$userPrompt

【方案甲】
$textA

【方案乙】
$textB

$Rubric
"@
    $body = @{
        model           = $Model
        messages        = @(@{ role = 'user'; content = $content })
        temperature     = 0.3
        # ★ 这个坑踩过：deepseek-v4-flash 是【推理模型】，会先输出 reasoning_content 再输出 content。
        #   最初给 900 太小，实测 reasoning_tokens 正好用满 900，finish_reason=length、content 为空，
        #   于是解析不到 JSON —— 而脚本把它静默当成 0 分，差点把整份盲评做成废数据。
        max_tokens      = 4000
        response_format = @{ type = 'json_object' }
        stream          = $false
    } | ConvertTo-Json -Depth 8 -Compress

    $r = Invoke-WebRequest -Uri "$BaseUrl/chat/completions" -Method Post `
        -Headers @{ Authorization = "Bearer $ApiKey"; 'Content-Type' = 'application/json' } `
        -Body ([Text.Encoding]::UTF8.GetBytes($body)) -UseBasicParsing -TimeoutSec 300
    $resp = ([Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())) | ConvertFrom-Json
    $choice = $resp.choices[0]
    $txt = $choice.message.content

    if (-not $txt -or $txt.Trim().Length -eq 0) {
        throw ("评审未返回正文（finish_reason=$($choice.finish_reason)，" +
            "reasoning_tokens=$($resp.usage.completion_tokens_details.reasoning_tokens)）" +
            "—— 通常是 max_tokens 被推理内容吃光了")
    }

    try { return ($txt | ConvertFrom-Json) }
    catch {
        # 兜底：从文本里抠出第一个 JSON 对象
        if ($txt -match '(?s)\{.*\}') { return ($Matches[0] | ConvertFrom-Json) }
        throw "评审返回无法解析为 JSON：$txt"
    }
}

# ---------------------------------------------------------------- 主流程
$scoreRows = New-Object System.Collections.Generic.List[object]
$mapRows = New-Object System.Collections.Generic.List[object]
$dims = @('facts', 'cover', 'action', 'struct', 'honest')

foreach ($g in $pairs) {
    $items = @($g.Group)
    # ★ 盲化：随机指派甲/乙，映射记下来
    if ((Get-Random -Minimum 0 -Maximum 2) -eq 0) {
        $asA = $items[0]; $asB = $items[1]
    } else {
        $asA = $items[1]; $asB = $items[0]
    }
    $promptId = $asA.promptId; $rep = $asA.rep
    Write-Host "  评审 $promptId r$rep ..." -NoNewline

    $scoresA = @{}; $scoresB = @{}
    foreach ($d in $dims) { $scoresA[$d] = @(); $scoresB[$d] = @() }

    $okCount = 0
    for ($i = 1; $i -le $Repeats; $i++) {
        try {
            $res = Invoke-Judge $asA.prompt $asA.answer $asB.answer
            foreach ($d in $dims) {
                if ($null -ne $res.A.$d) { $scoresA[$d] += [int]$res.A.$d }
                if ($null -ne $res.B.$d) { $scoresB[$d] += [int]$res.B.$d }
            }
            $okCount++
        } catch {
            Write-Host " 第 $i 次评分失败：$($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    if ($okCount -eq 0) {
        Write-Host " ✗ $promptId r$rep 的 $Repeats 次评分【全部失败】，该组无有效分数" -ForegroundColor Red
    } elseif ($okCount -lt $Repeats) {
        Write-Host " ⚠ $promptId r$rep 仅 $okCount/$Repeats 次成功" -ForegroundColor Yellow
    }

    function Median($arr) {
        if ($arr.Count -eq 0) { return $null }
        $s = $arr | Sort-Object
        $n = $s.Count
        if ($n % 2 -eq 1) { return $s[[int](($n - 1) / 2)] }
        return [math]::Round(($s[$n / 2 - 1] + $s[$n / 2]) / 2, 1)
    }

    # 分组标签要能区分「臂 + 预算档」：EXP-001 的记录只有 arm，EXP-002 还带 budget
    function LabelOf($rec) {
        if ($rec.PSObject.Properties.Name -contains 'budget' -and $rec.budget) {
            return "$($rec.arm)/b$($rec.budget)"
        }
        return [string]$rec.arm
    }

    $rowA = [ordered]@{ promptId = $promptId; rep = $rep; arm = (LabelOf $asA); runId = $asA.runId }
    $rowB = [ordered]@{ promptId = $promptId; rep = $rep; arm = (LabelOf $asB); runId = $asB.runId }
    foreach ($d in $dims) { $rowA[$d] = Median $scoresA[$d]; $rowB[$d] = Median $scoresB[$d] }
    $rowA['total'] = ($dims | ForEach-Object { $rowA[$_] } | Measure-Object -Sum).Sum
    $rowB['total'] = ($dims | ForEach-Object { $rowB[$_] } | Measure-Object -Sum).Sum

    $scoreRows.Add([pscustomobject]$rowA)
    $scoreRows.Add([pscustomobject]$rowB)
    $mapRows.Add([pscustomobject]@{ promptId = $promptId; rep = $rep; labelA_arm = $asA.arm; labelB_arm = $asB.arm })

    Write-Host ("  {0}: 甲({1})={2}  乙({3})={4}" -f $promptId, $asA.arm, $rowA['total'], $asB.arm, $rowB['total'])
}

$scoreRows | Export-Csv -Path (Join-Path $OutDir 'score.csv') -NoTypeInformation -Encoding UTF8
$mapRows | Export-Csv -Path (Join-Path $OutDir 'judge-mapping.csv') -NoTypeInformation -Encoding UTF8

Write-Host ''
Write-Host '=== 按臂汇总（5 维总分 = 满分 25）===' -ForegroundColor Cyan
$scoreRows | Group-Object arm | ForEach-Object {
    $g = $_.Group
    $avg = ($g | Measure-Object total -Average).Average
    $line = "  {0,-7} n={1}  总分均值 {2}" -f $_.Name, $g.Count, [math]::Round($avg, 2)
    foreach ($d in $dims) {
        $line += ("  {0}={1}" -f $d, [math]::Round((($g | Measure-Object $d -Average).Average), 2))
    }
    Write-Host $line
}
Write-Host ''
Write-Host "  score.csv        : $(Join-Path $OutDir 'score.csv')"
Write-Host "  judge-mapping.csv: $(Join-Path $OutDir 'judge-mapping.csv')"
