# Manual command-line build of the LSPatch/Xposed module
# (sync Tmall Campus activationKey -> unlock app io.github.ababc0324.openthedoor)
#
# Requirements (external toolchain, NOT bundled in this repo):
#   - JDK 21+                 -> $env:JAVA_HOME
#   - Android SDK             -> $env:ANDROID_SDK_ROOT  (build-tools;android-14 + platforms;android-34)
#   - Xposed API 82 (compile-only; framework provides it at runtime)
#     -> $env:XPOSED_API  OR  $PSScriptRoot\lib\xposed-api-82.jar
$ErrorActionPreference = "Stop"

$modDir = $PSScriptRoot

# ---- toolchain from environment (portable; no hard-coded machine paths) ----
if (-not $env:JAVA_HOME) { throw "JAVA_HOME not set (JDK 21+ required)" }
if (-not $env:ANDROID_SDK_ROOT) { throw "ANDROID_SDK_ROOT not set (Android SDK required)" }
$jdk = $env:JAVA_HOME
$tools = Join-Path $env:ANDROID_SDK_ROOT "build-tools\android-14"
$platform = Join-Path $env:ANDROID_SDK_ROOT "platforms\android-34"
if (-not (Test-Path $tools)) { throw "build-tools;android-14 not found under $env:ANDROID_SDK_ROOT" }
if (-not (Test-Path $platform)) { throw "platforms;android-34 not found under $env:ANDROID_SDK_ROOT" }

$androidJar = Join-Path $platform "android.jar"

# aapt2 (native C++) cannot stat android.jar on a Chinese path; use ASCII copy
# (kept outside the workspace under Temp so it does not clutter the repo)
$asciiCache = Join-Path $env:TEMP "opencode\unlock_build_cache"
New-Item -ItemType Directory -Force -Path $asciiCache | Out-Null
$androidJarAscii = Join-Path $asciiCache "android-34.jar"
if (-not (Test-Path $androidJarAscii)) { Copy-Item $androidJar $androidJarAscii -Force }

# Xposed API (compile-only; framework provides it at runtime). Fetched from TMallKey repo.
$xposedApi = $env:XPOSED_API
if (-not $xposedApi) { $xposedApi = Join-Path $modDir "lib\xposed-api-82.jar" }
if (-not (Test-Path $xposedApi)) { throw "xposed-api-82.jar not found: set $env:XPOSED_API or place at $xposedApi" }

$javac = Join-Path $jdk "bin\javac.exe"
$java = Join-Path $jdk "bin\java.exe"
$keytool = Join-Path $jdk "bin\keytool.exe"
$jar = Join-Path $jdk "bin\jar.exe"

$aapt2 = Join-Path $tools "aapt2.exe"
$zipalign = Join-Path $tools "zipalign.exe"
$d8Jar = Join-Path $tools "lib\r8-8.2.47.jar"
$apksignerJar = Join-Path $tools "lib\apksigner.jar"

$classesDir = Join-Path $modDir "build\classes"
$dexDir = Join-Path $modDir "build\dex"
$outDir = Join-Path $modDir "build\out"

# 0. clean
foreach ($d in @($classesDir, $dexDir, $outDir)) {
    if (Test-Path $d) { Remove-Item -Recurse -Force $d }
}
New-Item -ItemType Directory -Force -Path $classesDir, $dexDir, $outDir | Out-Null

Write-Host "== 1/6 javac =="
# Use explicit file enumeration instead of a *.java wildcard: javac's wildcard
# expansion splits on spaces and fails under a path like "E:\Opencode work".
$srcDir = Join-Path $modDir "src\com\example\doorunlock\sync"
$sources = @(Get-ChildItem $srcDir -Filter "*.java" | ForEach-Object { $_.FullName })
& $javac --release 8 -cp "$xposedApi;$androidJar" -d $classesDir $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== 2/6 jar =="
$classesJar = Join-Path $modDir "build\classes.jar"
Push-Location $classesDir
& $jar cf $classesJar .
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "== 3/6 d8 =="
# --lib: compile-time classpath for resolution; ONLY classes.jar is emitted (Xposed classes not bundled)
& $java -cp $d8Jar com.android.tools.r8.D8 --release --min-api 21 `
    --output $dexDir --lib $androidJar --lib $xposedApi $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== 4/6 aapt2 compile res + link =="
# aapt2 (native) cannot stat Chinese paths; use ASCII copies for res/assets
$resAscii = Join-Path $asciiCache "unlock_module_res"
$assetsAscii = Join-Path $asciiCache "unlock_module_assets"
if (Test-Path $resAscii) { Remove-Item -Recurse -Force $resAscii }
if (Test-Path $assetsAscii) { Remove-Item -Recurse -Force $assetsAscii }
Copy-Item -Recurse -Force (Join-Path $modDir "res") $resAscii
Copy-Item -Recurse -Force (Join-Path $modDir "assets") $assetsAscii

$resZip = Join-Path $modDir "build\res.zip"
& $aapt2 compile --dir $resAscii -o $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

$apkRaw = Join-Path $outDir "module.unaligned.apk"
& $aapt2 link -o $apkRaw -I $androidJarAscii --manifest (Join-Path $modDir "AndroidManifest.xml") `
    -A $assetsAscii $resZip --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== 5/6 add dex =="
$dexFile = Get-ChildItem $dexDir -Filter "*.dex" | Select-Object -First 1
Push-Location $dexFile.DirectoryName
& $jar uf $apkRaw $dexFile.Name
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "add dex failed" }

Write-Host "== 6/6 zipalign + sign =="
$aligned = Join-Path $outDir "module.aligned.apk"
& $zipalign -f 4 $apkRaw $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$keystore = Join-Path $modDir "build\syncmodule.jks"
# Keystore password stays out of source control: read it from the environment.
$ksPass = $env:DOORUNLOCK_KEYSTORE_PASS
if (-not $ksPass) { throw "DOORUNLOCK_KEYSTORE_PASS is not set (keystore password required)." }
if (-not (Test-Path $keystore)) {
    & $keytool -genkeypair -v -keystore $keystore -storepass $ksPass -keypass $ksPass `
        -alias syncmodule -keyalg RSA -keysize 2048 -validity 3650 `
        -dname "CN=DoorUnlockSync, OU=Dev, O=Example, L=City, ST=State, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$finalApk = Join-Path $outDir "doorunlock-sync-module.apk"
& $java -jar $apksignerJar sign --ks $keystore --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
    --out $finalApk $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host ""
Write-Host "BUILD OK: $finalApk"
Get-Item $finalApk | Select-Object FullName, Length
