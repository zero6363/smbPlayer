# 构建脚本：初始化 Android SDK 并编译 APK
# 用法：powershell -ExecutionPolicy Bypass -File build.ps1 [-Task assembleDebug|sdk|clean]
# 默认任务为 assembleRelease：每次都出正式签名包（D:\code\sdk\signing）
param(
    [string]$Task = "assembleRelease"
)

$ErrorActionPreference = "Stop"

# 控制台按 UTF-8 输出，避免中文提示乱码
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ProjectDir = $PSScriptRoot
$SdkDir     = "D:\code\sdk"
$JavaHome   = Join-Path $SdkDir "jdk"
$GradleHome = Join-Path $SdkDir "gradle-8.9"
$AndroidSdk = Join-Path $SdkDir "android-sdk"
$CmdTools   = Join-Path $AndroidSdk "cmdline-tools\latest\bin"

$env:JAVA_HOME   = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:PATH = (Join-Path $JavaHome "bin") + ";" + $env:PATH

$SdkManager = Join-Path $CmdTools "sdkmanager.bat"
$Grader     = Join-Path $GradleHome "bin\gradle.bat"

function Invoke-SdkSetup {
    Write-Host "== 安装 Android SDK 组件 ==" -ForegroundColor Cyan
    # 接受许可
    $licenses = Join-Path $AndroidSdk "licenses"
    New-Item -ItemType Directory -Force -Path $licenses | Out-Null
    Set-Content -Path (Join-Path $licenses "android-sdk-license") -Value "`n8933bad161af4178b1185d1a37fbf41ea5269c55`nd56f5187479451eabf01fb78af6dfcb131a6481e`n24333f8a63b6825ea9c5514f83c2829b004d1fee"
    Set-Content -Path (Join-Path $licenses "android-sdk-preview-license") -Value "`n84831b9409646a918e30573bab4c9c91346d8abd"
    # sdkmanager 可能仍会对未知许可提问，灌入足够的 "y" 防止卡住
    $yes = ("y`n" * 40)
    $yes | & $SdkManager --sdk_root=$AndroidSdk --licenses | Out-Null

    Install-SdkPackages @(
        "platform-tools",
        "platforms;android-35",
        "build-tools;35.0.0"
    )
}

function Install-SdkPackages([string[]]$packages) {
    # PowerShell 直接调用 .bat 时会丢失引号，导致 "platforms;android-35" 被拆成两个参数。
    # 必须经由 cmd.exe 显式加引号转传。
    $quoted = $packages | ForEach-Object { '"{0}"' -f $_ }
    $argLine = "--sdk_root=`"$AndroidSdk`" " + ($quoted -join " ")
    cmd /c "`"$SdkManager`" $argLine"
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed to install: $($packages -join ', ')" }
}

function Invoke-Gradle([string]$args_) {
    Write-Host "== gradle $args_ ==" -ForegroundColor Cyan
    & $Grader -p $ProjectDir --no-daemon $args_.Split(" ")
    if ($LASTEXITCODE -ne 0) { throw "gradle failed: $args_" }
}

switch ($Task) {
    "sdk"       { Invoke-SdkSetup }
    "clean"     { Invoke-Gradle "clean" }
    default     {
        if (-not (Test-Path (Join-Path $AndroidSdk "platforms\android-35"))) {
            Invoke-SdkSetup
        }
        Invoke-Gradle $Task
        $apk = Join-Path $ProjectDir "app\build\outputs\apk\debug\app-debug.apk"
        if (Test-Path $apk) {
            $info = Get-Item $apk
            Write-Host ("APK: {0}  ({1:N1} MB)" -f $info.FullName, ($info.Length / 1MB)) -ForegroundColor Green
        }
        $apkRel = Join-Path $ProjectDir "app\build\outputs\apk\release\app-release.apk"
        if (Test-Path $apkRel) {
            $info = Get-Item $apkRel
            Write-Host ("APK: {0}  ({1:N1} MB)" -f $info.FullName, ($info.Length / 1MB)) -ForegroundColor Green
            # 拷一份到项目根目录 output\，不用翻 app\build\outputs\apk\release 深路径
            $outDir = Join-Path $ProjectDir "output"
            New-Item -ItemType Directory -Force -Path $outDir | Out-Null
            Copy-Item -Path $apkRel -Destination (Join-Path $outDir "app-release.apk") -Force
            Write-Host ("APK: {0}" -f (Join-Path $outDir "app-release.apk")) -ForegroundColor Green
        }
    }
}
