$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$xmlFiles = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\build\test-results\testDebugUnitTest') -Filter 'TEST-*.xml'
if ($xmlFiles.Count -lt 5) { throw 'Нет полного набора локальных XML-отчётов. Запустите testDebugUnitTest без фильтра.' }
$total = 0
foreach ($file in $xmlFiles) {
    [xml]$suite = Get-Content -LiteralPath $file.FullName -Raw
    $total += [int]$suite.testsuite.tests
    if ([int]$suite.testsuite.failures -ne 0 -or [int]$suite.testsuite.errors -ne 0 -or [int]$suite.testsuite.skipped -ne 0) { throw "Неуспешный тест: $($file.Name)" }
}
[xml]$lint = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build\reports\lint-results-debug.xml') -Raw
$errors = @($lint.issues.issue | Where-Object { $_.severity -in 'Error','Fatal' })
$warnings = @($lint.issues.issue | Where-Object { $_.severity -eq 'Warning' })
if ($errors.Count) { throw "Lint: $($errors.Count) ошибок" }
# This release is verified on the host only. Never reuse historical device XML.
$destination = Join-Path $projectRoot 'dist\MyJournal-1.0.1.apk'
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk') -Destination $destination -Force
$verifyDir = Join-Path $projectRoot 'dist\verification'
New-Item -ItemType Directory -Force -Path $verifyDir | Out-Null
$signature = & "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs $destination 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Подпись APK не прошла проверку' }
$signature | Set-Content -LiteralPath (Join-Path $verifyDir 'signature.txt') -Encoding utf8
$badging = & "$env:ANDROID_HOME\build-tools\36.0.0\aapt2.exe" dump badging $destination 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Не удалось прочитать манифест APK' }
$badging | Set-Content -LiteralPath (Join-Path $verifyDir 'manifest.txt') -Encoding utf8
$manifest = $badging -join "`n"
if ($manifest -notmatch "package: name='com.nsfr.myjournal' versionCode='2' versionName='1\.0\.1'") { throw 'Неверный package или версия APK' }
if ($manifest -match 'android.permission.(INTERNET|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|READ_MEDIA_IMAGES|READ_MEDIA_VIDEO|READ_MEDIA_AUDIO)') { throw 'Обнаружено ненужное разрешение' }
$hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
$sourceHash = (Get-FileHash -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk') -Algorithm SHA256).Hash
if ($sourceHash -ne $hash) { throw 'Контрольные суммы доставленного APK и сборки различаются' }
$report = [ordered]@{
    apk = $destination
    bytes = (Get-Item -LiteralPath $destination).Length
    sha256 = $hash
    package = 'com.nsfr.myjournal'
    versionName = '1.0.1'
    versionCode = 2
    localTests = $total
    testFailures = 0
    lintErrors = 0
    lintWarnings = $warnings.Count
    signatureVerified = $true
    forbiddenPermissions = $false
    deviceTests = 'Not run for 1.0.1 (host-only verification requested)'
    date = (Get-Date).ToString('o')
}
$report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $verifyDir 'report.json') -Encoding utf8
$report | ConvertTo-Json
