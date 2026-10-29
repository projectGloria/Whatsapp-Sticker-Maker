$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$toolRoot = Join-Path $project '.tools'
New-Item -ItemType Directory -Force $toolRoot | Out-Null
function Get-Archive($url, $archive, $destination) {
    Invoke-WebRequest -Uri $url -OutFile $archive
    Expand-Archive -LiteralPath $archive -DestinationPath $destination -Force
    Remove-Item -LiteralPath $archive
}
if (-not (Test-Path -LiteralPath "$toolRoot/jdk-17.0.17+10/bin/java.exe")) {
    Get-Archive 'https://aka.ms/download-jdk/microsoft-jdk-17.0.17-windows-x64.zip' "$toolRoot/jdk.zip" $toolRoot
}
$env:JAVA_HOME = "$toolRoot/jdk-17.0.17+10"
$sdk = "$toolRoot/android-sdk"
if (-not (Test-Path -LiteralPath "$sdk/cmdline-tools/latest/bin/sdkmanager.bat")) {
    Get-Archive 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' "$toolRoot/sdk.zip" "$toolRoot/sdk-stage"
    New-Item -ItemType Directory -Force "$sdk/cmdline-tools" | Out-Null
    Move-Item -LiteralPath "$toolRoot/sdk-stage/cmdline-tools" -Destination "$sdk/cmdline-tools/latest"
}
Write-Host 'Review and accept the SDK licenses to install the Android build tools.'
& "$sdk/cmdline-tools/latest/bin/sdkmanager.bat" "--sdk_root=$sdk" --licenses
& "$sdk/cmdline-tools/latest/bin/sdkmanager.bat" "--sdk_root=$sdk" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools' 'ndk;27.0.12077973' 'cmake;3.22.1'
if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed.' }
"sdk.dir=$($sdk.Replace('\','/'))" | Set-Content -LiteralPath "$project/local.properties"
Write-Host 'Ready. Run scripts/build.ps1 to build Sticker Studio.'
