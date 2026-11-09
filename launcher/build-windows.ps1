# Windows version of build-and-install.sh (build part): Android Studio's JDK + the SDK build-tools, no Gradle.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
# Android Studio's JDK and SDK on a PC; on GitHub Actions the runner's JDK and SDK (JAVA_HOME / ANDROID_SDK).
if (Test-Path 'C:\Program Files\Android\Android Studio\jbr') { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
$J = "$env:JAVA_HOME\bin"
$SDK = if ($env:ANDROID_SDK) { $env:ANDROID_SDK } elseif (Test-Path "$env:LOCALAPPDATA\Android\Sdk") { "$env:LOCALAPPDATA\Android\Sdk" } else { $env:ANDROID_HOME }
$BT = "$SDK\build-tools\36.0.0"
$JAR = "$SDK\platforms\android-34\android.jar"
$B = 'build'
if (Test-Path $B) { Remove-Item -Recurse -Force $B }
New-Item -ItemType Directory "$B\gen", "$B\classes", "$B\dex" | Out-Null
& "$BT\aapt2.exe" compile --dir res -o "$B\res.zip"; if ($LASTEXITCODE) { throw 'aapt2 compile' }
& "$BT\aapt2.exe" link -o "$B\base.apk" -I $JAR --manifest AndroidManifest.xml --java "$B\gen" --min-sdk-version 30 --target-sdk-version 33 "$B\res.zip"; if ($LASTEXITCODE) { throw 'aapt2 link' }
$src = Get-ChildItem -Recurse src, "$B\gen" -Filter *.java | % FullName
& "$J\javac.exe" -nowarn -Xlint:-options -encoding UTF-8 --release 11 -classpath $JAR -d "$B\classes" $src; if ($LASTEXITCODE) { throw 'javac' }
$cls = Get-ChildItem -Recurse "$B\classes" -Filter *.class | % FullName
& "$BT\d8.bat" --min-api 30 --lib $JAR --output "$B\dex" $cls; if ($LASTEXITCODE) { throw 'd8' }
Copy-Item "$B\base.apk" "$B\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::Open((Resolve-Path "$B\unsigned.apk"), 'Update')
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($z, (Resolve-Path "$B\dex\classes.dex"), 'classes.dex') | Out-Null
# assets with forward slashes (aapt2 -A on Windows writes backslashes, which Android cannot find)
$ar = (Resolve-Path assets).Path
Get-ChildItem -Recurse -File assets | % { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($z, $_.FullName, 'assets/' + $_.FullName.Substring($ar.Length + 1).Replace('\','/'), 'NoCompression') | Out-Null }
$z.Dispose()
& "$BT\zipalign.exe" -f 4 "$B\unsigned.apk" "$B\aligned.apk"; if ($LASTEXITCODE) { throw 'zipalign' }
# Same signing key on every PC and on GitHub (keystore.b64), so a new launcher always installs over the old one.
if (Test-Path keystore.b64) { $KS = "$B\sign.keystore"; [IO.File]::WriteAllBytes((Join-Path (Resolve-Path $B).Path 'sign.keystore'), [Convert]::FromBase64String((Get-Content keystore.b64 -Raw).Trim())) }
else { $KS = 'debug.keystore'; if (-not (Test-Path debug.keystore)) { & "$J\keytool.exe" -genkeypair -keystore debug.keystore -storepass android -keypass android -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=tvhome' 2>$null } }
& "$BT\apksigner.bat" sign --ks $KS --ks-pass pass:android --out "$B\tvhome.apk" "$B\aligned.apk"; if ($LASTEXITCODE) { throw 'apksigner' }
"built $B\tvhome.apk ($((Get-Item "$B\tvhome.apk").Length) bytes)"



