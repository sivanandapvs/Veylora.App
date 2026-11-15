$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$propertiesPath = Join-Path $repo 'keystore.properties'
$keystore = Join-Path $repo 'veylora-release.jks'
$wrapper = Join-Path $repo 'gradlew.bat'
if (!(Test-Path (Join-Path $repo '.git')) -or !(Test-Path $wrapper)) { throw 'Repository or Gradle wrapper not found.' }
if (!(Test-Path $propertiesPath) -or !(Test-Path $keystore)) { throw 'Release signing files not found.' }
# Load Java properties without sending their contents to the console.
$props = @{}
Get-Content -LiteralPath $propertiesPath | ForEach-Object {
    if ($_ -match '^\s*([^#!\s=]+)\s*=\s*(.*)\s*$') { $props[$Matches[1]] = $Matches[2].Trim() }
}
foreach ($name in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
    if (!$props[$name]) { throw "Missing signing property: $name" }
}
$configured = [IO.Path]::GetFullPath((Join-Path $repo $props.storeFile))
if ($configured -ne [IO.Path]::GetFullPath($keystore)) { throw 'Configured keystore differs from expected release keystore.' }
$env:VEYLORA_LOCAL_STORE_PASSWORD = $props.storePassword
try {
    $certificate = & keytool -exportcert -rfc -keystore $keystore -alias $props.keyAlias -storepass:env VEYLORA_LOCAL_STORE_PASSWORD 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Could not open release keystore or alias.' }
    $pem = ($certificate | Where-Object { $_ -notmatch '^-----' }) -join ''
    $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2 (,[Convert]::FromBase64String($pem))
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $expected = ([BitConverter]::ToString($sha.ComputeHash($cert.RawData))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose(); $cert.Dispose() }
} finally { Remove-Item Env:VEYLORA_LOCAL_STORE_PASSWORD -ErrorAction SilentlyContinue }
Push-Location $repo
try { & $wrapper ':app:assembleRelease' '--no-daemon' '-x' ':app:uploadSentryProguardMappingsRelease'; if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' } } finally { Pop-Location }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (!$sdk) { throw 'Android SDK location is not configured.' }
$tools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name -Descending | Where-Object { (Test-Path (Join-Path $_.FullName 'apksigner.bat')) -and (Test-Path (Join-Path $_.FullName 'aapt.exe')) } | Select-Object -First 1
if (!$tools) { throw 'Android SDK build tools not found.' }
$metadata = Get-ChildItem (Join-Path $repo 'app/build/outputs/apk') -Filter output-metadata.json -Recurse | Where-Object { $_.FullName -match '[\\/]release[\\/]' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (!$metadata) { throw 'Release APK metadata not found.' }
$output = Get-Content -Raw -LiteralPath $metadata.FullName | ConvertFrom-Json
$element = $output.elements | Where-Object { $_.filters.Count -eq 0 } | Select-Object -First 1
if (!$element) { throw 'Universal APK not found.' }
$apk = Join-Path $metadata.DirectoryName $element.outputFile
$badging = & (Join-Path $tools.FullName 'aapt.exe') dump badging $apk
if ($LASTEXITCODE -ne 0 -or $badging[0] -cnotmatch "package: name='app\.veylora\.player'.*? versionName='([^']+)' ") { throw 'Unexpected APK package or version.' }
$version = $Matches[1]
$verification = & (Join-Path $tools.FullName 'apksigner.bat') verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$certLine = $verification | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)$' } | Select-Object -First 1
if (!$certLine -or $certLine -notmatch '^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)$' -or $Matches[1].ToLowerInvariant() -ne $expected) { throw 'Unexpected APK signing certificate.' }
$destination = Join-Path $env:USERPROFILE 'Veylora-Builds'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$final = Join-Path $destination "Veylora-$version-universal.apk"
Copy-Item -LiteralPath $apk -Destination $final -Force
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $final).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$final.sha256", "$hash  $([IO.Path]::GetFileName($final))`n", [Text.Encoding]::ASCII)
Write-Host "APK: $final`nVersion: $version`nRelease certificate verified: $expected`nSHA-256: $hash"
