$ErrorActionPreference = 'Stop'
$signingDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\local-signing'))
if (Test-Path (Join-Path $signingDir 'myjournal.p12')) { throw 'Ключ уже существует; перезапись запрещена.' }
New-Item -ItemType Directory -Force -Path $signingDir | Out-Null
$secret = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
$env:MYJOURNAL_SIGNING_PASSWORD = $secret
try {
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore (Join-Path $signingDir 'myjournal.p12') -storetype PKCS12 -storepass:env MYJOURNAL_SIGNING_PASSWORD -keypass:env MYJOURNAL_SIGNING_PASSWORD -alias myjournal -keyalg RSA -keysize 4096 -validity 36500 -dname 'CN=MyJournal, OU=Local, O=NSFR, C=RU'
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать ключ.' }
    [IO.File]::WriteAllText((Join-Path $signingDir 'release.properties'), "storePassword=$secret`nkeyPassword=$secret`n", [Text.UTF8Encoding]::new($false))
} finally { Remove-Item Env:\MYJOURNAL_SIGNING_PASSWORD; $secret = $null }
Write-Output 'Release-ключ создан. Сохраните local-signing отдельно в безопасном месте.'
