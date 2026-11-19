param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JdkPath = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
if (-not $SdkPath) {
    $SdkPath = Join-Path (Split-Path -Parent $projectPath) 'HotspotApp\.tools\android-sdk'
}
if ($projectPath -match '[^\x00-\x7F]') {
    $mappingRoot = Split-Path -Parent $projectPath
    $projectFolder = Split-Path -Leaf $projectPath
    $driveLetter = 90..69 | ForEach-Object { [char]$_ } | Where-Object { -not (Test-Path "${_}:\") } | Select-Object -First 1
    if (-not $driveLetter) {
        throw 'No available drive letter for the Android build tools'
    }
    & subst "${driveLetter}:" $mappingRoot
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not map the project to an ASCII path'
    }
    try {
        if ($SdkPath.StartsWith($mappingRoot + "\", [StringComparison]::OrdinalIgnoreCase)) {
            $SdkPath = "${driveLetter}:" + $SdkPath.Substring($mappingRoot.Length)
        }
        & "${driveLetter}:\$projectFolder\tools\build-apk.ps1" -SdkPath $SdkPath -JdkPath $JdkPath
    } finally {
        & subst "${driveLetter}:" /D
    }
    return
}
if (-not $JdkPath) {
    $javaProperties = & java.exe -XshowSettings:properties -version 2>&1
    $JdkPath = ($javaProperties | Select-String '^\s*java.home = (.+)$').Matches.Groups[1].Value
}
$buildToolsPath = Join-Path $SdkPath 'build-tools\35.0.0'
$androidJar = Join-Path $SdkPath 'platforms\android-35\android.jar'
$outputPath = Join-Path $projectPath 'app\build\standalone'
$apkPath = Join-Path $projectPath 'dist\MuteToggle.apk'
$keyStorePath = Join-Path $projectPath '.tools\mute-debug.keystore'
$appConfig = Get-Content -LiteralPath "$projectPath\app\build.gradle" -Raw
$applicationId = [regex]::Match($appConfig, "applicationId\s+'([^']+)'").Groups[1].Value
$minimumSdk = [regex]::Match($appConfig, 'minSdk\s+(\d+)').Groups[1].Value
$targetSdk = [regex]::Match($appConfig, 'targetSdk\s+(\d+)').Groups[1].Value
$versionCode = [regex]::Match($appConfig, 'versionCode\s+(\d+)').Groups[1].Value
$versionName = [regex]::Match($appConfig, "versionName\s+'([^']+)'").Groups[1].Value

function Invoke-Checked {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable failed with exit code $LASTEXITCODE"
    }
}

foreach ($requiredPath in @($androidJar, "$buildToolsPath\aapt2.exe", "$JdkPath\bin\javac.exe")) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required build tool not found: $requiredPath"
    }
}
New-Item -ItemType Directory -Force -Path $outputPath, "$outputPath\generated", "$outputPath\classes", "$outputPath\dex", (Split-Path -Parent $apkPath), (Split-Path -Parent $keyStorePath) | Out-Null

[xml]$manifest = Get-Content -LiteralPath "$projectPath\app\src\main\AndroidManifest.xml" -Raw -Encoding UTF8
$manifest.manifest.SetAttribute('package', $applicationId)
$manifestPath = Join-Path $outputPath 'AndroidManifest.xml'
$manifest.Save($manifestPath)

Invoke-Checked "$buildToolsPath\aapt2.exe" @('compile', '--dir', "$projectPath\app\src\main\res", '-o', "$outputPath\compiled-res.zip")
Invoke-Checked "$buildToolsPath\aapt2.exe" @('link', '-o', "$outputPath\resources.ap_", '--manifest', $manifestPath, '-I', $androidJar, '--java', "$outputPath\generated", '--min-sdk-version', $minimumSdk, '--target-sdk-version', $targetSdk, '--version-code', $versionCode, '--version-name', $versionName, "$outputPath\compiled-res.zip")
$sourcePaths = @(Get-ChildItem "$projectPath\app\src\main\java", "$outputPath\generated" -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
Invoke-Checked "$JdkPath\bin\javac.exe" (@('-encoding', 'UTF-8', '--release', '8', '-classpath', $androidJar, '-d', "$outputPath\classes") + $sourcePaths)
Invoke-Checked "$JdkPath\bin\jar.exe" @('cf', "$outputPath\classes.jar", '-C', "$outputPath\classes", '.')
Invoke-Checked "$JdkPath\bin\java.exe" @('-cp', "$buildToolsPath\lib\d8.jar", 'com.android.tools.r8.D8', '--min-api', $minimumSdk, '--lib', $androidJar, '--output', "$outputPath\dex", "$outputPath\classes.jar")
Copy-Item -LiteralPath "$outputPath\resources.ap_" -Destination "$outputPath\unsigned.apk" -Force
Invoke-Checked "$JdkPath\bin\jar.exe" @('uf', "$outputPath\unsigned.apk", '-C', "$outputPath\dex", 'classes.dex')
Invoke-Checked "$buildToolsPath\zipalign.exe" @('-f', '4', "$outputPath\unsigned.apk", "$outputPath\aligned.apk")

if (-not (Test-Path -LiteralPath $keyStorePath)) {
    Invoke-Checked "$JdkPath\bin\keytool.exe" @('-genkeypair', '-keystore', $keyStorePath, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Android Debug,O=Android,C=US')
}
Invoke-Checked "$JdkPath\bin\java.exe" @('-jar', "$buildToolsPath\lib\apksigner.jar", 'sign', '--ks', $keyStorePath, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v4-signing-enabled', 'false', '--out', $apkPath, "$outputPath\aligned.apk")
Invoke-Checked "$JdkPath\bin\java.exe" @('-jar', "$buildToolsPath\lib\apksigner.jar", 'verify', '--verbose', '--min-sdk-version', $minimumSdk, $apkPath)
Invoke-Checked "$buildToolsPath\zipalign.exe" @('-c', '4', $apkPath)
Get-Item -LiteralPath $apkPath | Select-Object FullName, Length
