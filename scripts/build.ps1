param([switch]$Test)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $project
$bundledJava = Join-Path $project '.tools/jdk-17.0.17+10'
if (Test-Path -LiteralPath $bundledJava) { $env:JAVA_HOME = $bundledJava }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17 installation, or run scripts/setup.ps1 first.' }
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
$tasks = @(':app:assembleDebug')
if ($Test) { $tasks += @(':app:testDebugUnitTest', ':app:lintDebug', ':app:connectedDebugAndroidTest') }
$gradle = Join-Path $project '.tools/gradle-8.11.1/bin/gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $project 'gradlew.bat' }
& $gradle @tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
New-Item -ItemType Directory -Force "$project/deliverables" | Out-Null
Copy-Item -LiteralPath "$project/app/build/outputs/apk/debug/app-debug.apk" -Destination "$project/deliverables/Nihil-and-catz.apk" -Force
Get-FileHash -LiteralPath "$project/deliverables/Nihil-and-catz.apk" -Algorithm SHA256
