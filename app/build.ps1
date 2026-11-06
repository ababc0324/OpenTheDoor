# Manual command-line build of "Open the door" (Tmall Campus VOC door lock standalone app)
# No Gradle. Requires JDK 21 + Android SDK (build-tools;android-14, platforms;android-34).
#   Set ANDROID_SDK_ROOT (or ANDROID_HOME) and JAVA_HOME, or place them next to this repo
#   under android-sdk/ and jdk21/ respectively.
$ErrorActionPreference = "Stop"

$appDir = $PSScriptRoot
$root = Split-Path $PSScriptRoot -Parent

# ---- resolve SDK / JDK ----
$sdkRoot = $null
if ($env:ANDROID_SDK_ROOT) { $sdkRoot = $env:ANDROID_SDK_ROOT }
elseif ($env:ANDROID_HOME) { $sdkRoot = $env:ANDROID_HOME }
elseif (Test-Path (Join-Path $root "android-sdk")) { $sdkRoot = Join-Path $root "android-sdk" }
if (-not $sdkRoot) { throw "Android SDK not found. Set ANDROID_SDK_ROOT (or ANDROID_HOME) to your SDK." }

$tools = Join-Path $sdkRoot "build-tools\android-14"
$platform = Join-Path $sdkRoot "platforms\android-34"

$jdk = $null
if ($env:JAVA_HOME) { $jdk = $env:JAVA_HOME }
elseif (Test-Path (Join-Path $root "jdk21\jdk-21.0.13.0.2+1")) { $jdk = Join-Path $root "jdk21\jdk-21.0.13.0.2+1" }
if (-not $jdk) { throw "JDK not found. Set JAVA_HOME to a JDK 21." }

if (-not (Test-Path $tools)) { throw "Android build-tools android-14 not found at $tools" }
if (-not (Test-Path $platform)) { throw "Android platform android-34 not found at $platform" }
if (-not (Test-Path (Join-Path $jdk "bin\javac.exe"))) { throw "javac not found under $jdk" }

$androidJar = Join-Path $platform "android.jar"
# aapt2 (native C++) cannot stat paths containing Chinese characters; use ASCII copies
# (kept under %TEMP%, outside the workspace)
$asciiCache = Join-Path $env:TEMP "opencode\unlock_build_cache"
New-Item -ItemType Directory -Force -Path $asciiCache | Out-Null
$androidJarAscii = Join-Path $asciiCache "android-34.jar"
if (-not (Test-Path $androidJarAscii)) {
    Copy-Item $androidJar $androidJarAscii -Force
}
# aapt2 cannot stat the res dir on a Chinese path either; refresh an ASCII copy each build
$resDir = Join-Path $appDir "res"
$resAscii = Join-Path $asciiCache "unlock_app_res"
if (Test-Path $resAscii) { Remove-Item -Recurse -Force $resAscii }
Copy-Item $resDir $resAscii -Recurse -Force

$javac = Join-Path $jdk "bin\javac.exe"
$java = Join-Path $jdk "bin\java.exe"
$keytool = Join-Path $jdk "bin\keytool.exe"
$jar = Join-Path $jdk "bin\jar.exe"

$aapt2 = Join-Path $tools "aapt2.exe"
$zipalign = Join-Path $tools "zipalign.exe"
$d8Jar = Join-Path $tools "lib\r8-8.2.47.jar"
$apksignerJar = Join-Path $tools "lib\apksigner.jar"

$classesDir = Join-Path $appDir "build\classes"
$dexDir = Join-Path $appDir "build\dex"
$outDir = Join-Path $appDir "build\out"

# 1. clean
if (Test-Path $classesDir) { Remove-Item -Recurse -Force $classesDir }
if (Test-Path $dexDir) { Remove-Item -Recurse -Force $dexDir }
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Force -Path $classesDir, $dexDir, $outDir | Out-Null

Write-Host "== 1/5 javac =="
# Enumerate source files explicitly: javac's *.java wildcard expansion fails on paths with spaces
$sources = @(Get-ChildItem (Join-Path $appDir "java\io\github\ababc0324\openthedoor") -Filter "*.java" |
    ForEach-Object { $_.FullName })
& $javac --release 8 -cp $androidJar -d $classesDir $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== 2/5 jar =="
$classesJar = Join-Path $appDir "build\classes.jar"
Push-Location $classesDir
& $jar cf $classesJar .
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "== 3/5 d8 =="
& $java -cp $d8Jar com.android.tools.r8.D8 --release --min-api 21 `
    --output $dexDir --lib $androidJar $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
Get-ChildItem $dexDir | Select-Object Name, Length

Write-Host "== 4/5 aapt2 compile + link + add dex =="
$compiledDir = Join-Path $asciiCache "unlock_app_res_compiled"
if (Test-Path $compiledDir) { Remove-Item -Recurse -Force $compiledDir }
New-Item -ItemType Directory -Force -Path $compiledDir | Out-Null
& $aapt2 compile --dir $resAscii -o $compiledDir
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
$flatFiles = @(Get-ChildItem $compiledDir -Filter "*.flat" | ForEach-Object { $_.FullName })
$apkRaw = Join-Path $outDir "app.unaligned.apk"
& $aapt2 link -o $apkRaw -I $androidJarAscii --manifest (Join-Path $appDir "AndroidManifest.xml") --auto-add-overlay $flatFiles
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$dexFile = Get-ChildItem $dexDir -Filter "*.dex" | Select-Object -First 1
Push-Location $dexFile.DirectoryName
& $jar uf $apkRaw $dexFile.Name
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "add dex failed" }

Write-Host "== 5/5 zipalign + sign =="
$aligned = Join-Path $outDir "app.aligned.apk"
& $zipalign -f 4 $apkRaw $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$keystore = Join-Path $appDir "build\doorunlock.jks"
# Keystore password stays out of source control: read it from the environment.
$ksPass = $env:DOORUNLOCK_KEYSTORE_PASS
if (-not $ksPass) { throw "DOORUNLOCK_KEYSTORE_PASS is not set (keystore password required)." }
if (-not (Test-Path $keystore)) {
    & $keytool -genkeypair -v -keystore $keystore -storepass $ksPass -keypass $ksPass `
        -alias doorunlock -keyalg RSA -keysize 2048 -validity 3650 `
        -dname "CN=DoorUnlock, OU=Dev, O=Example, L=City, ST=State, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$finalApk = Join-Path $outDir "doorunlock.apk"
& $java -jar $apksignerJar sign --ks $keystore --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
    --out $finalApk $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host ""
Write-Host "BUILD OK: $finalApk"
Get-Item $finalApk | Select-Object FullName, Length
