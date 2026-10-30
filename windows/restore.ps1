# Volta a TV ao que era: reativa todas as apps desativadas e repoe o ecra inicial original do Google TV.
# Uso (TV ligada, mesma rede):  powershell -ExecutionPolicy Bypass -File windows\restore.ps1 [-Tv 192.168.1.128]
param([string]$Tv = '192.168.1.128')
$root = Split-Path $PSScriptRoot -Parent
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s = "${Tv}:5555"
& $adb connect $s
$list = Get-Content "$root\examples\tcl-google-tv-g08\debloat.txt" | ForEach-Object { ($_ -replace '#.*','').Trim() } | Where-Object { $_ }
foreach ($p in $list) { & $adb -s $s shell pm enable $p }
& $adb -s $s shell cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity
& $adb -s $s shell input keyevent KEYCODE_HOME
"Reposto. Para remover tambem o ecra inicial novo: adb uninstall com.mihir.tvhome"
