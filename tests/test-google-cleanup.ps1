#requires -Version 5.1
$ErrorActionPreference='Stop'
$source=Join-Path (Split-Path $PSScriptRoot -Parent) 'tools/disable-haier-google-extras.ps1'
$tokens=$null;$parseErrors=$null
$null=[System.Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count -gt 0){throw ($parseErrors | Out-String)}
. $source
function Assert-True {param([bool]$Condition,[string]$Message)if(-not $Condition){throw $Message}}
function Reset-Fixture {
    $script:MockStates=@{
        'com.android.vending'=0;'com.google.android.gms'=0;'com.google.android.gsf'=0;
        'com.google.android.inputmethod.latin'=0;'com.google.android.tv.remote.service'=0;
        'com.google.android.tvlauncher'=0;'com.google.android.tvrecommendations'=0;
        'com.google.android.katniss'=0;'com.google.android.play.games'=1;
        'com.google.android.youtube.tv'=0;'com.google.android.youtube.tvmusic'=3
    }
    $script:MockCommands=@();$script:MockFail='';$script:MockInput='';$script:MockDevice='daan'
    $script:Mode='Apply';$script:BackupPath=Join-Path $env:TEMP ('haier-cleanup-test-'+[guid]::NewGuid().ToString('N'))
}
function Invoke-HaierAdb {
    param([string[]]$Arguments)
    $text=$Arguments -join ' ';$script:MockCommands += $text
    switch($text){
        'shell getprop ro.product.device' {return $script:MockDevice}
        'shell getprop ro.serialno' {return 'TEST-TV-SERIAL'}
        'shell getprop ro.product.model' {return 'Haier_Android_TV_FF_Pro'}
        'shell am get-current-user' {return '0'}
        'shell cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME' {return 'com.google.android.tvlauncher/.MainActivity'}
        'shell settings get secure default_input_method' {return 'com.google.android.inputmethod.latin/.LatinIME'}
        'shell pm list packages --user 0' {return (($script:MockStates.Keys | Sort-Object | ForEach-Object {'package:'+$_}) -join "`n")}
    }
    if($text -match '^shell dumpsys package ([A-Za-z0-9_.]+)$'){
        $p=$Matches[1];if(-not $script:MockStates.ContainsKey($p)){throw 'Unknown fixture package'}
        $dependency=if($p -eq $script:MockInput){'android.permission.TV_INPUT_HARDWARE'}else{''}
        return "Package [$p]:`n  userId=10123`n  requested permissions:`n    $dependency`n  User 0: installed=true hidden=false suspended=false stopped=false notLaunched=false enabled=$($script:MockStates[$p])"
    }
    if($text -match '^shell pm (disable-user|default-state|enable|disable|disable-until-used) --user 0 ([A-Za-z0-9_.]+)$'){
        $verb=$Matches[1];$p=$Matches[2]
        Assert-True ($script:Extras -contains $p) 'Attempt to mutate a package outside the allowlist'
        $value=switch($verb){'default-state'{0}'enable'{1}'disable'{2}'disable-user'{3}'disable-until-used'{4}}
        $script:MockStates[$p]=$value
        if($verb -eq 'disable-user' -and $p -eq $script:MockFail){$script:MockFail='';throw 'Simulated transport failure after mutation'}
        return "Package $p new state: $value"
    }
    throw "Unexpected ADB command in test: $text"
}
Reset-Fixture
$script:Mode='Inspect'
Start-HaierGoogleCleanup
Assert-True (@($script:MockCommands | Where-Object {$_ -match '^shell pm (disable|enable|default-state)'}).Count -eq 0) 'Inspect performed a mutation'
Assert-True (-not (Test-Path -LiteralPath $script:BackupPath)) 'Inspect created a rollback directory'
Write-Host 'PASS: inspect performs no mutation'

Reset-Fixture
$script:MockInput='com.google.android.youtube.tv'
$before=$script:MockStates.Clone()
Start-HaierGoogleCleanup
Assert-True ($script:MockStates['com.google.android.katniss'] -eq 3) 'Assistant was not disabled'
Assert-True ($script:MockStates['com.google.android.play.games'] -eq 3) 'Optional media was not disabled'
Assert-True ($script:MockStates['com.google.android.youtube.tv'] -eq 0) 'Input-dependent package was disabled'
Assert-True ($script:MockStates['com.google.android.youtube.tvmusic'] -eq 3) 'Existing disabled state changed'
Assert-True ($script:MockStates['com.android.vending'] -eq 0 -and $script:MockStates['com.google.android.gms'] -eq 0) 'Store/core package changed'
$journalPath=Join-Path $script:BackupPath 'journal.json'
$journal=Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
Assert-True (@($journal.Actions).Count -eq 2) 'Unexpected mutation journal'
Assert-True (Test-Path -LiteralPath (Join-Path $script:BackupPath 'restore-google.ps1')) 'Rollback entry point missing'
Restore-HaierJournal $journal
foreach($p in $before.Keys){Assert-True ($script:MockStates[$p] -eq $before[$p]) "Restore failed for $p"}
Remove-Item -LiteralPath $script:BackupPath -Recurse -Force
Write-Host 'PASS: allowlist, input guard, absent/already-disabled skips, backup, exact restore of default/enabled states'

Reset-Fixture
$before=$script:MockStates.Clone();$script:MockFail='com.google.android.play.games';$failed=$false
try{Start-HaierGoogleCleanup}catch{$failed=$true}
Assert-True $failed 'Injected failure was not reported'
foreach($p in $before.Keys){Assert-True ($script:MockStates[$p] -eq $before[$p]) "Automatic rollback failed for $p"}
$journal=Get-Content -LiteralPath (Join-Path $script:BackupPath 'journal.json') -Raw | ConvertFrom-Json
Assert-True (@($journal.Actions | Where-Object {$_.Package -eq 'com.google.android.play.games' -and $_.Attempted}).Count -eq 1) 'Interrupted mutation missing from journal'
Remove-Item -LiteralPath $script:BackupPath -Recurse -Force
Write-Host 'PASS: interruption after a mutation rolls back all attempted packages'

Reset-Fixture
$script:MockDevice='other';$failed=$false
try{Start-HaierGoogleCleanup}catch{$failed=$true}
Assert-True $failed 'Wrong device was accepted'
Assert-True (@($script:MockCommands | Where-Object {$_ -match '^shell pm (disable|enable|default-state)'}).Count -eq 0) 'Wrong device was modified'
Write-Host 'PASS: device mismatch fails before mutation'

$failed=$false
try{Set-HaierEnabledState -Package 'com.google.android.gms' -State 3}catch{$failed=$true}
Assert-True $failed 'Protected package accepted by mutation boundary'
Write-Host 'PASS: mutation boundary refuses Google Play Services'
