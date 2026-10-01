param([switch]$RestoreOnly)
$ErrorActionPreference='Stop'
try {
    $taskAdb=Join-Path $PSScriptRoot 'phone-tools\adb.exe'
    $taskDevices=& $taskAdb devices
    if($LASTEXITCODE -ne 0){throw 'Could not start the phone connection.'}
    $taskSerials=@($taskDevices | ForEach-Object {if($_ -match '^([^\s]+)\s+device$'){$Matches[1]}})
    $taskMatches=@($taskSerials | Where-Object {(& $taskAdb -s $_ shell getprop ro.product.model).Trim() -eq 'SM-S918W'})
    if($taskMatches.Count -ne 1){throw 'Connect one SM-S918W by USB, unlock it, and accept the USB debugging prompt. Then try again.'}
    $taskSerial=$taskMatches[0]
    function Invoke-Phone([string[]]$PhoneArguments) {
        $taskResult=& $taskAdb -s $taskSerial @PhoneArguments
        if($LASTEXITCODE -ne 0){throw ($taskResult -join "`n")}
        return $taskResult
    }
    Write-Host 'Preparing Screen Safe...'
    $taskFilter='ca.screensafe.app/ca.screensafe.app.TouchFilterService'
    $taskServices=(Invoke-Phone @('shell','settings','get','secure','enabled_accessibility_services') -join '').Trim()
    $taskOthers=@($taskServices -split ':' | Where-Object {$_ -and $_ -ne 'null' -and $_ -ne $taskFilter -and $_ -ne 'ca.screensafe.app/.TouchFilterService'})
    if($taskServices -match 'ca.screensafe.app/'){
        if($taskOthers.Count){Invoke-Phone @('shell','settings','put','secure','enabled_accessibility_services',($taskOthers -join ':'))|Out-Null}
        else {Invoke-Phone @('shell','settings','delete','secure','enabled_accessibility_services')|Out-Null;Invoke-Phone @('shell','settings','put','secure','accessibility_enabled','0')|Out-Null}
    }
    Invoke-Phone @('shell','am','force-stop','ca.screensafe.app') | Out-Null
    $taskReady=$false
    for($taskAttempt=0;$taskAttempt -lt 12;$taskAttempt++){
        $taskDisplays=Invoke-Phone @('shell','dumpsys','window','displays')
        if(-not($taskDisplays -match 'OneHanded:.*\(organized\)')){$taskReady=$true;break}
        Start-Sleep -Seconds 1
    }
    if(-not $taskReady){throw 'Another display feature is active. Stop one-handed mode and try again.'}
    Invoke-Phone @('push',(Join-Path $PSScriptRoot 'screensafe-backend.dex'),'/data/local/tmp/screensafe-backend.dex') | Out-Null
    $taskRecovery=Invoke-Phone @('shell','env','CLASSPATH=/data/local/tmp/screensafe-backend.dex','app_process','/system/bin','ScreenSafeBackend','recover')
    if(($taskRecovery -match 'ERROR:|RESTORE_ERROR') -or -not($taskRecovery -match '^RESTORED$')){throw ($taskRecovery -join "`n")}
    if($RestoreOnly){Write-Host 'The full screen is restored. You can disconnect USB.';exit 0}
    Invoke-Phone @('install','-r','--user','0',(Join-Path $PSScriptRoot 'ScreenSafe.apk')) | Out-Null
    Invoke-Phone @('shell','nohup am instrument -w -r -e protect true -e test adaptive ca.screensafe.app/.SessionRunner > /data/local/tmp/screensafe-session.log 2>&1 < /dev/null &') | Out-Null
    Start-Sleep -Seconds 3
    # Add only our service, retaining any services the user already has enabled.
    $taskServices=(Invoke-Phone @('shell','settings','get','secure','enabled_accessibility_services') -join '').Trim()
    $taskOthers=@($taskServices -split ':' | Where-Object {$_ -and $_ -ne 'null' -and $_ -ne $taskFilter})
    Invoke-Phone @('shell','settings','put','secure','enabled_accessibility_services',(($taskOthers + $taskFilter) -join ':'))|Out-Null
    Invoke-Phone @('shell','settings','put','secure','accessibility_enabled','1')|Out-Null
    $taskFilterReady=$false
    for($taskAttempt=0;$taskAttempt -lt 10;$taskAttempt++){
        $taskFilterDump=Invoke-Phone @('shell','dumpsys','activity','service','ca.screensafe.app/.TouchFilterService')
        if($taskFilterDump -match 'ScreenSafe touch filter: active=true'){$taskFilterReady=$true;break}
        Start-Sleep -Seconds 1
    }
    if(-not $taskFilterReady){throw 'Protection did not start. Check the status in Screen Safe. Its touch filter may need enabling in Accessibility settings.'}
    $taskLog=Invoke-Phone @('shell','cat','/data/local/tmp/screensafe-session.log')
    if($taskLog -match 'error=|Process crashed|INSTRUMENTATION_FAILED'){throw ($taskLog -join "`n")}
    Write-Host 'Five-minute experimental trial active. Keep USB connected; wallpaper, rotation, and recovery still need verification.'
} catch {
    Write-Host ('Screen Safe: '+$_.Exception.Message) -ForegroundColor Red
    exit 1
}
