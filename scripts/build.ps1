param([switch]$DebugBuild)
$ErrorActionPreference='Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
if (-not $env:JAVA_HOME -and (Test-Path 'C:\Program Files\Java\jdk-21.0.10')) { $env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10' }
if (-not $DebugBuild -and -not (Test-Path signing.properties)) {
    New-Item -ItemType Directory -Force .keys | Out-Null
    $env:CupboxSigningPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $keytool=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin\keytool.exe'}else{'keytool'}
    & $keytool -genkeypair -keystore .keys/cupbox-release.jks -alias cupbox -keyalg RSA -keysize 3072 -validity 10000 -storepass:env CupboxSigningPassword -keypass:env CupboxSigningPassword -dname 'CN=Cupbox Android, O=Cupbox, C=CN'
    if($LASTEXITCODE -ne 0){throw '签名密钥创建失败'}
    @("storeFile=.keys/cupbox-release.jks","storePassword=$env:CupboxSigningPassword",'keyAlias=cupbox',"keyPassword=$env:CupboxSigningPassword") | Set-Content signing.properties -Encoding ascii
    Remove-Item Env:CupboxSigningPassword
}
$variant=if($DebugBuild){'Debug'}else{'Release'}
& .\gradlew.bat "assemble$variant" testDebugUnitTest lintDebug --console=plain
if($LASTEXITCODE -ne 0){throw '构建或检查失败'}
New-Item -ItemType Directory -Force release | Out-Null
$source="app/build/outputs/apk/$($variant.ToLower())/app-$($variant.ToLower()).apk"
$destination="release/Cupbox-Android-Qbot-0.1.0-$($variant.ToLower()).apk"
Copy-Item -LiteralPath $source -Destination $destination -Force
$hash=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLower()
"$hash  $([IO.Path]::GetFileName($destination))" | Set-Content "$destination.sha256" -Encoding ascii
Write-Output "已生成 $destination"
