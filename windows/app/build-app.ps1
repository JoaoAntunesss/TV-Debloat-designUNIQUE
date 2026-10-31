# Builds TVDebloat.exe: the launcher APK, the window (ui.html) and a wallpaper go inside the .exe.
# Needs only what the launcher build needs (Android Studio) plus the C# compiler that comes with Windows.
# Uso:  powershell -ExecutionPolicy Bypass -File windows\app\build-app.ps1
$ErrorActionPreference = 'Stop'
$app = $PSScriptRoot
$root = Split-Path (Split-Path $app -Parent) -Parent
& powershell -ExecutionPolicy Bypass -File "$root\launcher\build-windows.ps1"
if ($LASTEXITCODE) { throw 'launcher build' }
$dist = "$root\dist"
New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item "$root\launcher\build\tvhome.apk" "$app\tvhome.apk" -Force
Copy-Item "$root\launcher\assets\wallpapers\01.jpg" "$app\wall.jpg" -Force
$csc = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
& $csc /nologo /target:winexe /optimize+ /codepage:65001 /out:"$dist\TVDebloat.exe" /win32icon:"$app\icon.ico" `
  /r:System.IO.Compression.dll /r:System.IO.Compression.FileSystem.dll `
  /resource:"$app\ui.html",ui.html /resource:"$app\wall.jpg",wall.jpg /resource:"$app\icon.png",icon.png /resource:"$app\tvhome.apk",tvhome.apk `
  "$app\TVDebloat.cs"
if ($LASTEXITCODE) { throw 'csc' }
Remove-Item "$app\tvhome.apk", "$app\wall.jpg"
"built $dist\TVDebloat.exe ($([math]::Round((Get-Item "$dist\TVDebloat.exe").Length / 1MB, 1)) MB)"
