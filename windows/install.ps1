# Instala tudo na TV TCL (Google TV) a partir do Windows: debloat, ecra inicial novo e permissoes.
# Requisitos: Android Studio (JDK + SDK build-tools 36 + platform 34) e a TV com depuracao de rede ligada.
# Uso:  powershell -ExecutionPolicy Bypass -File windows\install.ps1 [-Tv 192.168.1.128]
param([string]$Tv = '192.168.1.128')
$root = Split-Path $PSScriptRoot -Parent
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s = "${Tv}:5555"
& $adb connect $s

# 1. copia de seguranca da lista de apps
$bk = "$root\windows\backup-$(Get-Date -f yyyyMMdd-HHmmss)"
New-Item -ItemType Directory $bk | Out-Null
& $adb -s $s shell pm list packages -f > "$bk\packages-f.txt"
& $adb -s $s shell pm list packages -d > "$bk\disabled.txt"

# 2. ecra inicial
& powershell -ExecutionPolicy Bypass -File "$root\launcher\build-windows.ps1"
& $adb -s $s push "$root\launcher\build\tvhome.apk" /data/local/tmp/tvhome.apk
& $adb -s $s shell pm install -r /data/local/tmp/tvhome.apk
& $adb -s $s shell rm -f /data/local/tmp/tvhome.apk
& $adb -s $s shell pm grant com.mihir.tvhome android.permission.ACCESS_FINE_LOCATION
& $adb -s $s shell pm grant com.mihir.tvhome android.permission.BLUETOOTH_CONNECT
& $adb -s $s shell cmd notification allow_listener com.mihir.tvhome/com.mihir.tvhome.MediaListener
& $adb -s $s shell appops set com.mihir.tvhome PROJECT_MEDIA allow

# 3. debloat (desativa, nunca desinstala; uma chamada pm por comando)
$list = Get-Content "$root\examples\tcl-google-tv-g08\debloat.txt" | ForEach-Object { ($_ -replace '#.*','').Trim() } | Where-Object { $_ }
foreach ($p in $list) { & $adb -s $s shell pm disable-user --user 0 $p }

# 4. ecra inicial por omissao (sobrevive a reiniciar)
& $adb -s $s shell cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity
& $adb -s $s shell am force-stop com.android.tv.settings
& $adb -s $s shell input keyevent KEYCODE_HOME
"Feito. Desfazer: windows\restore.ps1"

