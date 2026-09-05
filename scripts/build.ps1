$ErrorActionPreference = 'Stop'
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '..\.gradle-user-home'
Push-Location (Join-Path $PSScriptRoot '..')
try { & .\gradlew.bat @args; if ($LASTEXITCODE -ne 0) { throw "Gradle: $LASTEXITCODE" } } finally { Pop-Location }
