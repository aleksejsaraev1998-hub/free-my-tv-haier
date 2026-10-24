param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateSet('stock','standard','lite','bento')][string]$Profile = 'stock',
    [string]$AdbPath = 'C:\platform-tools\platform-tools\adb.exe',
    [string]$OutputDirectory = '.'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw 'adb.exe not found' }
$connection = & $AdbPath -s $Serial get-state 2>&1
if ($LASTEXITCODE -ne 0 -or ($connection -join '').Trim() -ne 'device') {
    throw 'ADB device unavailable. Check adb devices and pass the current -Serial.'
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$destination = Join-Path $OutputDirectory "haier-$Profile-$stamp"
New-Item -ItemType Directory -Path $destination -ErrorAction Stop | Out-Null
function Save-AdbOutput {
    param([string]$Name, [string[]]$CommandArgs)
    $result = & $AdbPath -s $Serial @CommandArgs 2>&1
    $result | Out-File -LiteralPath (Join-Path $destination $Name) -Encoding utf8
    if ($LASTEXITCODE -ne 0) { Write-Warning "Could not collect $Name; see saved output." }
}
# Read-only ADB commands. No reset, install, enable/disable or settings writes.
Save-AdbOutput 'memory-total.txt' @('shell','cat','/proc/meminfo')
Save-AdbOutput 'disabled-packages.txt' @('shell','pm','list','packages','-d')
Save-AdbOutput 'home.txt' @('shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
Save-AdbOutput 'display.txt' @('shell','wm','size')
Save-AdbOutput 'stock-memory.txt' @('shell','dumpsys','meminfo','com.google.android.tvlauncher')
if ($Profile -ne 'stock') {
    $previewPackage = 'io.freemytv.haierpreview'
    if ($Profile -eq 'lite' -or $Profile -eq 'bento') { $previewPackage += '.' + $Profile }
    Save-AdbOutput 'preview-memory.txt' @('shell','dumpsys','meminfo',$previewPackage)
    Save-AdbOutput 'preview-frames.txt' @('shell','dumpsys','gfxinfo',$previewPackage,'framestats')
}
Write-Host "Read-only snapshot saved: $destination"
