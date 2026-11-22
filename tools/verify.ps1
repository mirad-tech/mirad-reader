<#
.SYNOPSIS
本地一键验证：运行 Gradle 检查，并把完整输出与摘要保存到 build/verification/。

.DESCRIPTION
默认依次运行 JVM 单测、preview 源集编译和 Debug 构建，使用 --continue 一次报告所有失败。

输出（build/ 不入 Git）：
  build/verification/verify-<时间戳>/gradle.log    完整 Gradle 输出（UTF-8）
  build/verification/verify-<时间戳>/summary.txt   结论、耗时、编译错误、失败任务与失败用例
  build/verification/verify-latest.txt             最近一次的结果目录

只做本地构建：不签名、不安装、不启动或操作任何设备与模拟器。

.EXAMPLE
.\tools\verify.ps1

.EXAMPLE
.\tools\verify.ps1 -Lint -Rerun

.EXAMPLE
.\tools\verify.ps1 -Tasks ':app:testDebugUnitTest'
#>
[CmdletBinding()]
param(
    # 要运行的 Gradle 任务。
    [string[]]$Tasks = @(':app:testDebugUnitTest', ':app:compilePreviewKotlin', ':app:assembleDebug'),
    # 追加 :app:lintDebug（历史存在 warning，以报告为准）。
    [switch]$Lint,
    # 追加带 Compose 编译器稳定性报告的未签名 Release 构建，报告在 app/build/compose_compiler/。
    [switch]$ComposeReports,
    # 传入 --rerun-tasks，忽略增量缓存。
    [switch]$Rerun
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$verificationRoot = Join-Path $projectRoot 'build/verification'
$outDir = Join-Path $verificationRoot ('verify-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$logPath = Join-Path $outDir 'gradle.log'
$summaryPath = Join-Path $outDir 'summary.txt'
$utf8 = New-Object System.Text.UTF8Encoding($false)

$gradleArgs = [System.Collections.Generic.List[string]]::new()
foreach ($task in $Tasks) { $gradleArgs.Add($task) }
if ($Lint) { $gradleArgs.Add(':app:lintDebug') }
if ($ComposeReports) {
    $gradleArgs.Add(':app:assembleRelease')
    $gradleArgs.Add('-PcomposeReports=true')
}
if ($Rerun) { $gradleArgs.Add('--rerun-tasks') }
$gradleArgs.Add('--continue')
$gradleArgs.Add('--console=plain')

$started = Get-Date
$writer = New-Object System.IO.StreamWriter($logPath, $false, $utf8)
$writer.AutoFlush = $true
$exitCode = 1
Push-Location $projectRoot
try {
    # 经 cmd 合并 stderr：Windows PowerShell 5.1 在 Stop 模式下会把原生命令写到 stderr 的内容当成异常。
    $argLine = $gradleArgs -join ' '
    cmd /c ".\gradlew.bat $argLine 2>&1" | ForEach-Object {
        $writer.WriteLine($_)
        Write-Host $_
    }
    $exitCode = $LASTEXITCODE
} finally {
    $writer.Dispose()
    Pop-Location
}
$elapsed = (Get-Date) - $started

# ---------------------------------------------------------------- 解析日志
$lines = @(Get-Content -LiteralPath $logPath -Encoding UTF8)
$compileErrors = @($lines | Where-Object { $_ -match '^e: ' })
$failedTasks = @($lines | Where-Object { $_ -match '^> Task (\S+) FAILED' } | ForEach-Object { $_ -replace '^> Task (\S+) FAILED.*$', '$1' })
$wrong = [System.Collections.Generic.List[string]]::new()
$inWrong = $false
foreach ($line in $lines) {
    if ($line -match '^\* What went wrong:') { $inWrong = $true; continue }
    if ($line -match '^\* (Try|Exception is|Get more help)') { $inWrong = $false; continue }
    if ($inWrong -and $wrong.Count -lt 80) { $wrong.Add($line) }
}

# ---------------------------------------------------------------- 本次生成的单测结果
$total = 0; $failed = 0; $skipped = 0; $freshSuites = 0
$testFailures = [System.Collections.Generic.List[string]]::new()
$resultsRoot = Join-Path $projectRoot 'app/build/test-results'
foreach ($dir in @(Get-ChildItem -LiteralPath $resultsRoot -Directory -ErrorAction SilentlyContinue)) {
    foreach ($file in @(Get-ChildItem -LiteralPath $dir.FullName -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)) {
        if ($file.LastWriteTime -lt $started) { continue }
        $freshSuites++
        [xml]$doc = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
        $suite = $doc.testsuite
        $total += [int]$suite.tests
        $failed += [int]$suite.failures + [int]$suite.errors
        $skipped += [int]$suite.skipped
        foreach ($case in @($suite.testcase)) {
            if ($null -eq $case) { continue }
            $problem = $case.SelectSingleNode('failure|error')
            if ($null -eq $problem) { continue }
            $message = $problem.GetAttribute('message')
            if (-not $message) { $message = $problem.InnerText }
            $firstLine = @($message -split "`r?`n" | Where-Object { $_.Trim() })[0]
            $testFailures.Add(('{0}.{1}: {2}' -f $case.classname, $case.name, $firstLine))
        }
    }
}

$gitInfo = '不可用'
if (Get-Command git -ErrorAction SilentlyContinue) {
    $gitInfo = & {
        $ErrorActionPreference = 'Continue'
        $head = git -C $projectRoot rev-parse --short HEAD 2>$null
        $changes = @(git -C $projectRoot status --porcelain 2>$null).Count
        if ($head) { '{0}，未提交改动 {1} 项' -f $head, $changes } else { '不可用' }
    }
}

# ---------------------------------------------------------------- 摘要
$result = if ($exitCode -eq 0) { '通过' } else { '失败' }
$s = [System.Collections.Generic.List[string]]::new()
$s.Add(('结果：{0}（退出码 {1}）' -f $result, $exitCode))
$s.Add(('开始：{0:yyyy-MM-dd HH:mm:ss}，耗时 {1}' -f $started, $elapsed.ToString('hh\:mm\:ss')))
$s.Add(('参数：{0}' -f ($gradleArgs -join ' ')))
$s.Add(('Git：{0}' -f $gitInfo))
$s.Add('')
$s.Add(('编译错误：{0}' -f $compileErrors.Count))
$compileErrors | Select-Object -First 50 | ForEach-Object { $s.Add('  ' + $_) }
if ($failedTasks.Count -gt 0) { $s.Add(('失败任务：{0}' -f ($failedTasks -join ', '))) }
if ($freshSuites -gt 0) {
    $s.Add(('单测：共 {0}，失败 {1}，跳过 {2}' -f $total, $failed, $skipped))
    $testFailures | Select-Object -First 50 | ForEach-Object { $s.Add('  ' + $_) }
} elseif ($gradleArgs -contains ':app:testDebugUnitTest') {
    $s.Add('单测：本次没有新的测试结果（任务未执行或为 UP-TO-DATE，可加 -Rerun）')
}
if ($wrong.Count -gt 0) {
    $s.Add('')
    $s.Add('Gradle 报告的问题：')
    $wrong | ForEach-Object { $s.Add('  ' + $_) }
}
$s.Add('')
$s.Add('报告与产物：')
$s.Add('  完整日志  ' + $logPath)
$s.Add('  单测报告  ' + (Join-Path $projectRoot 'app/build/reports/tests/testDebugUnitTest/index.html'))
if ($Lint) { $s.Add('  Lint      ' + (Join-Path $projectRoot 'app/build/reports/lint-results-debug.html')) }
if ($ComposeReports) { $s.Add('  Compose   ' + (Join-Path $projectRoot 'app/build/compose_compiler')) }
$apk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
if ((Test-Path -LiteralPath $apk) -and (Get-Item -LiteralPath $apk).LastWriteTime -ge $started) { $s.Add('  Debug APK ' + $apk) }

[System.IO.File]::WriteAllLines($summaryPath, $s, $utf8)
[System.IO.File]::WriteAllText((Join-Path $verificationRoot 'verify-latest.txt'), $outDir, $utf8)
Write-Host ''
Write-Host '===== 验证摘要 ====='
$s | ForEach-Object { Write-Host $_ }
exit $exitCode
