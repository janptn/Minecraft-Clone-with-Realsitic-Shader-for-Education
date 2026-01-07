$ErrorActionPreference = 'Stop'

$javaHome = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
if (-not (Test-Path $javaHome)) {
    throw "Temurin JDK not found at $javaHome. Install Java 17 first."
}

$env:JAVA_HOME = $javaHome
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$gradleBat = Join-Path $PSScriptRoot '.gradle-dist\gradle-8.7\bin\gradle.bat'
if (-not (Test-Path $gradleBat)) {
    Write-Host 'Gradle not found; downloading...'
    $distDir = Join-Path $PSScriptRoot '.gradle-dist'
    New-Item -ItemType Directory -Force $distDir | Out-Null

    $zip = Join-Path $distDir 'gradle-8.7-bin.zip'
    if (-not (Test-Path $zip)) {
        Invoke-WebRequest -Uri https://services.gradle.org/distributions/gradle-8.7-bin.zip -OutFile $zip
    }
    Expand-Archive -Path $zip -DestinationPath $distDir -Force
}

& $gradleBat run
