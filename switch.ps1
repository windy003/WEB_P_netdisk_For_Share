$root = $PSScriptRoot
$envFile = Join-Path $root 'switch.env'
$switchDir = Join-Path $root 'switch'
$appDir = Join-Path $root 'NoNeedLogin'
$appPyPath = Join-Path $appDir 'app.py'

function Get-WaitSeconds {
    param([string]$Key, [int]$Default = 5)
    try {
        $line = Get-Content $envFile -ErrorAction Stop | Where-Object { $_ -match "^$Key\s*=\s*([0-9\s\*]+)" } | Select-Object -First 1
        if ($line -and $Matches[1]) {
            $expr = $Matches[1].Trim()
            if ($expr -match '^[0-9]+(\s*\*\s*[0-9]+)*$') {
                $result = 1L
                foreach ($part in ($expr -split '\*')) { $result *= [long]$part.Trim() }
                if ($result -gt 0) { return [int]$result }
            }
        }
    } catch {}
    return $Default
}

function Stop-Matching {
    param([string]$Pattern1, [string]$Pattern2)
    $procs = Get-CimInstance Win32_Process | Where-Object {
        $_.CommandLine -and $_.CommandLine -like $Pattern1 -and (-not $Pattern2 -or $_.CommandLine -like $Pattern2)
    }
    foreach ($p in $procs) {
        try {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
            Write-Host "  已结束进程 PID=$($p.ProcessId)"
        } catch {
            Write-Host "  结束进程 PID=$($p.ProcessId) 失败: $($_.Exception.Message)"
        }
    }
    if ($procs) { Start-Sleep -Milliseconds 800 }
}

Write-Host "赞美诗页面自动切换脚本已启动 (关闭此窗口以停止)"

while ($true) {
    $appRunning = [bool](Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*NoNeedLogin*app.py*' })

    if ($appRunning) {
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] 检测到赞美诗服务正在运行 -> 切换到广告页"
        Stop-Matching -Pattern1 '*NoNeedLogin*app.py*'
        Start-Process -FilePath 'python' -ArgumentList @('-m', 'http.server', '5001', '-d', $switchDir) -WindowStyle Hidden
        $wait = Get-WaitSeconds -Key 'Static_Page_Lasting'
    } else {
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] 未检测到赞美诗服务 -> 切换回赞美诗页面"
        Stop-Matching -Pattern1 '*http.server*' -Pattern2 '*5001*'
        Start-Process -FilePath 'pythonw' -ArgumentList @("`"$appPyPath`"") -WorkingDirectory $appDir -WindowStyle Hidden
        $wait = Get-WaitSeconds -Key 'No_Need_Login_Page_Lasting'
    }

    Write-Host "  等待 $wait 秒..."
    Start-Sleep -Seconds $wait
}
