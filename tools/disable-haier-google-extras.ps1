#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('Inspect','Apply','Restore')][string]$Mode = 'Inspect',
    [string]$TV = '192.168.1.215:45053',
    [string]$AdbPath = (Join-Path $PSScriptRoot 'adb.exe'),
    [string]$BackupPath = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Deliberately closed allowlist. No wildcard package removal and no uninstall/clear commands.
$script:Extras = @(
    'com.google.android.katniss',
    'com.android.hotwordenrollment.okgoogle',
    'com.android.hotwordenrollment.xgoogle',
    'com.google.android.youtube.tv',
    'com.google.android.youtube.tvmusic',
    'com.google.android.play.games',
    'com.google.android.videos'
)
$script:Keep = @(
    'com.android.vending','com.google.android.gms','com.google.android.gsf',
    'com.google.android.inputmethod.latin','com.google.android.tv.remote.service',
    'com.google.android.tvlauncher','com.google.android.tvrecommendations',
    'com.google.android.tungsten.setupwraith','com.google.android.partnersetup',
    'com.google.android.permissioncontroller','com.google.android.packageinstaller',
    'com.google.android.webview','com.google.android.ext.services',
    'com.google.android.apps.mediashell','com.google.android.tts',
    'com.google.android.syncadapters.calendar','com.android.tv.settings',
    'com.android.systemui','com.mediatek.tv.service','com.mediatek.tvinput'
)
function Invoke-HaierAdb {
    param([string[]]$Arguments)
    $lines = & $AdbPath -s $TV @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ('ADB failed: ' + ($lines -join "`n")) }
    return ($lines -join "`n").Trim()
}
function Get-HaierPackageState {
    param([string]$Package)
    $dump = Invoke-HaierAdb -Arguments @('shell','dumpsys','package',$Package)
    $match = [regex]::Match($dump,'(?m)^\s*User 0:[^\r\n]*\benabled=(\d+)\b')
    if (-not $match.Success) { throw "Cannot read user-0 enabled state: $Package" }
    $installed = [regex]::Match($match.Value,'\binstalled=(true|false)\b')
    if (-not $installed.Success -or $installed.Groups[1].Value -ne 'true') { throw "Package not installed for user 0: $Package" }
    return [pscustomobject]@{ State = [int]$match.Groups[1].Value; Dump = $dump }
}
function Get-HaierIdentity {
    $device = Invoke-HaierAdb -Arguments @('shell','getprop','ro.product.device')
    $model = Invoke-HaierAdb -Arguments @('shell','getprop','ro.product.model')
    if ($device -ne 'daan' -or $model -notmatch '(?i)Haier') { throw "Unexpected device: $model / $device. Nothing changed." }
    $user = Invoke-HaierAdb -Arguments @('shell','am','get-current-user')
    if ($user -ne '0') { throw 'Only the reviewed owner profile (user 0) is supported. Nothing changed.' }
    $deviceSerial = Invoke-HaierAdb -Arguments @('shell','getprop','ro.serialno')
    return [pscustomobject]@{ Model=$model; Device=$device; DeviceSerial=$deviceSerial }
}
function Get-HaierBasics {
    $identity = Get-HaierIdentity
    $homeResult = Invoke-HaierAdb -Arguments @('shell','cmd','package','resolve-activity','--brief','--user','0','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
    $match = [regex]::Match($homeResult,'(?m)^([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\s*$')
    if (-not $match.Success -or $match.Value -match 'ResolverActivity|FallbackHome') { throw 'No unambiguous HOME activity. Nothing changed.' }
    $ime = Invoke-HaierAdb -Arguments @('shell','settings','get','secure','default_input_method')
    if ($ime -notmatch '^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$') { throw 'Cannot identify the current keyboard. Nothing changed.' }
    $installed = Invoke-HaierAdb -Arguments @('shell','pm','list','packages','--user','0')
    $packages = @([regex]::Matches($installed,'(?m)^package:([A-Za-z0-9_.]+)\s*$') | ForEach-Object { $_.Groups[1].Value })
    if ($packages.Count -eq 0) { throw 'Cannot read installed packages. Nothing changed.' }
    $critical = @()
    foreach ($package in $script:Keep) {
        if ($packages -contains $package) {
            $critical += [pscustomobject]@{ Package = $package; State = (Get-HaierPackageState $package).State }
        }
    }
    return [pscustomobject]@{ Model=$identity.Model; Device=$identity.Device; DeviceSerial=$identity.DeviceSerial; Home=$match.Groups[1].Value; IME=$ime; Packages=$packages; Critical=$critical }
}
function Get-HaierPlan {
    param($Basics)
    $plan = @()
    foreach ($package in $script:Extras) {
        $row = [ordered]@{ Package=$package; Before=-1; Action='skip'; Reason='not installed' }
        if ($Basics.Packages -contains $package) {
            $state = Get-HaierPackageState $package
            $row.Before = $state.State
            if ($script:Keep -contains $package -or $Basics.Home.StartsWith($package + '/') -or $Basics.IME.StartsWith($package + '/')) {
                $row.Reason = 'protected HOME / keyboard / core package'
            } elseif ($state.Dump -match 'TV_INPUT_HARDWARE|TV_INPUT_BUTTON|CONNECT_INPUT|AddAccessory|\bPERSISTENT\b|sharedUserId=android.uid.system|sharedUser=.*android.uid.system|\buserId=1000\b|\bappId=1000\b') {
                $row.Reason = 'TV input / remote / persistent / shared-system dependency'
            } elseif ($state.State -notin @(0,1)) {
                $row.Reason = 'already disabled or not in the normal enabled state'
            } else { $row.Action='disable-user'; $row.Reason='optional Google voice/media app' }
        }
        $plan += [pscustomobject]$row
    }
    return $plan
}
function Save-HaierJournal {
    param($Journal,[string]$Path)
    $temp = $Path + '.tmp'
    $Journal | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $temp -Encoding UTF8
    Move-Item -LiteralPath $temp -Destination $Path -Force
}
function Set-HaierEnabledState {
    param([string]$Package,[int]$State)
    if ($script:Extras -notcontains $Package -or $script:Keep -contains $Package) { throw "Refusing package outside allowlist: $Package" }
    $verb = switch ($State) { 0 {'default-state'} 1 {'enable'} 2 {'disable'} 3 {'disable-user'} 4 {'disable-until-used'} default {throw 'Invalid enabled state'} }
    $null = Invoke-HaierAdb -Arguments @('shell','pm',$verb,'--user','0',$Package)
    $actual = (Get-HaierPackageState $Package).State
    if ($actual -ne $State) { throw "Enabled-state verification failed: $Package" }
}
function Restore-HaierJournal {
    param($Journal)
    $errors = @()
    $actions = @($Journal.Actions)
    [array]::Reverse($actions)
    foreach ($action in $actions) {
        if ($action.Attempted) {
            try {
                Set-HaierEnabledState -Package $action.Package -State ([int]$action.Before)
                Write-Host ('RESTORED: ' + $action.Package)
            } catch { $errors += $_.Exception.Message }
        }
    }
    if ($errors.Count -gt 0) { throw ('Rollback incomplete: ' + ($errors -join '; ')) }
}
function Assert-HaierBasicsUnchanged {
    param($Before)
    $after = Get-HaierBasics
    if ($after.Home -ne $Before.Home -or $after.IME -ne $Before.IME) { throw 'HOME or keyboard changed unexpectedly.' }
    foreach ($original in $Before.Critical) {
        $current = @($after.Critical | Where-Object { $_.Package -eq $original.Package })
        if ($current.Count -ne 1 -or $current[0].State -ne $original.State) { throw ('Protected package state changed: ' + $original.Package) }
    }
}
function Start-HaierGoogleCleanup {
    if ($Mode -eq 'Restore') {
        if ([string]::IsNullOrWhiteSpace($BackupPath)) { throw 'Restore requires -BackupPath pointing to journal.json.' }
        $basics = Get-HaierIdentity
        $journal = Get-Content -LiteralPath $BackupPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($journal.Schema -ne 1 -or $journal.Device -ne $basics.Device -or $journal.Model -ne $basics.Model) { throw 'Backup belongs to a different device/model.' }
        if (-not [string]::IsNullOrWhiteSpace($journal.DeviceSerial) -and -not [string]::IsNullOrWhiteSpace($basics.DeviceSerial) -and $journal.DeviceSerial -ne $basics.DeviceSerial) { throw 'Backup belongs to another physical TV.' }
        Restore-HaierJournal $journal
        Write-Host 'Restored recorded package enabled states. No reboot required.'
        return
    }
    $basics = Get-HaierBasics
    $plan = @(Get-HaierPlan $basics)
    $plan | Format-Table Package,Action,Reason -AutoSize | Out-Host
    if ($Mode -eq 'Inspect') { Write-Host 'Inspect only. No changes.'; return }
    $targets = @($plan | Where-Object { $_.Action -eq 'disable-user' })
    if ($targets.Count -eq 0) { Write-Host 'No eligible packages. No changes.'; return }
    if ([string]::IsNullOrWhiteSpace($BackupPath)) { $BackupPath = Join-Path $PSScriptRoot ('haier-google-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
    $directory = [System.IO.Path]::GetFullPath($BackupPath)
    if (Test-Path -LiteralPath $directory) { throw 'Backup directory already exists. Refusing to overwrite it.' }
    $null = New-Item -ItemType Directory -Path $directory
    $journalPath = Join-Path $directory 'journal.json'
    $journal = [pscustomobject]@{ Schema=1; Model=$basics.Model; Device=$basics.Device; DeviceSerial=$basics.DeviceSerial; Serial=$TV; AdbPath=[System.IO.Path]::GetFullPath($AdbPath); Created=(Get-Date -Format o); Home=$basics.Home; IME=$basics.IME; Plan=$plan; Actions=@() }
    Save-HaierJournal $journal $journalPath
    Copy-Item -LiteralPath $script:HaierScriptFile -Destination (Join-Path $directory 'disable-haier-google-extras.ps1')
    @'
param([string]$TV = '',[string]$AdbPath = '')
$ErrorActionPreference = 'Stop'
$journal = Join-Path $PSScriptRoot 'journal.json'
$saved = Get-Content -LiteralPath $journal -Raw -Encoding UTF8 | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($TV)) { $TV = $saved.Serial }
if ([string]::IsNullOrWhiteSpace($AdbPath)) { $AdbPath = $saved.AdbPath }
& (Join-Path $PSScriptRoot 'disable-haier-google-extras.ps1') -Mode Restore -TV $TV -AdbPath $AdbPath -BackupPath $journal
'@ | Set-Content -LiteralPath (Join-Path $directory 'restore-google.ps1') -Encoding UTF8
    Write-Host ('BACKUP: ' + $directory)
    try {
        foreach ($target in $targets) {
            $record = [pscustomobject]@{ Package=$target.Package; Before=$target.Before; Attempted=$true; Applied=$false }
            $journal.Actions += $record
            # Persist intent before the command so interruption cannot lose the rollback entry.
            Save-HaierJournal $journal $journalPath
            Set-HaierEnabledState -Package $target.Package -State 3
            $record.Applied = $true
            Save-HaierJournal $journal $journalPath
            Write-Host ('DISABLED: ' + $target.Package)
        }
        Assert-HaierBasicsUnchanged $basics
    } catch {
        $cause = $_.Exception.Message
        try { Restore-HaierJournal $journal } catch { throw ($cause + '; ' + $_.Exception.Message + '; backup: ' + $directory) }
        throw ($cause + '; changes rolled back. Backup: ' + $directory)
    }
    Write-Host 'DONE. Google voice search / Assistant and the listed optional media apps are disabled.'
    Write-Host 'Play Store, Google core services, stock HOME, keyboard, TV inputs and remote services were preserved.'
    Write-Host ('ROLLBACK: powershell -NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $directory 'restore-google.ps1') + '"')
}
$script:HaierScriptFile = $PSCommandPath
# Tests dot-source definitions and replace the ADB boundary; no TV is contacted by the test harness.
if ($MyInvocation.InvocationName -eq '.') { return }
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw "ADB not found: $AdbPath. Put this script beside adb.exe." }
Start-HaierGoogleCleanup
