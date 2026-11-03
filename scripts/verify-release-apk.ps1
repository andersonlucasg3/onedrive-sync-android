param(
    [string]$Apk = (Join-Path $PSScriptRoot "..\app\build\outputs\apk\release\app-release.apk"),
    [string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" })
)

# Fails the build when R8 removed a class the app reaches by name at runtime (Application/Activity
# from the manifest, WorkManager workers) or a generated kotlinx.serialization serializer. Runs
# without a device: the APK is inspected with apkanalyzer and apksigner.
$ErrorActionPreference = "Stop"

$RequiredClasses = @(
    'com.anderson.onedrivesync.App'
    'com.anderson.onedrivesync.ui.MainActivity'
    'com.anderson.onedrivesync.sync.SyncEngine'
    'com.anderson.onedrivesync.graph.GraphClient'
    'com.anderson.onedrivesync.work.SyncWorker'
    'com.anderson.onedrivesync.update.UpdateChecker'
    'com.anderson.onedrivesync.graph.DeltaPage$$serializer'
    'com.anderson.onedrivesync.graph.DriveItem$$serializer'
    'com.anderson.onedrivesync.update.ReleaseDto$$serializer'
)

# Resources the app needs at runtime. `ic_notification` is used through a compile-time constant, so
# the shrinker cannot see the reference (see app/src/main/res/raw/keep.xml).
$RequiredResources = @(
    'drawable/ic_notification'
    'mipmap/ic_launcher'
    'style/Theme.OneDriveSync'
    'xml/file_paths'
    'string/app_name'
)

function Find-Tool([string]$Name, [string]$VersionGlob) {
    $found = Get-ChildItem -Path (Join-Path $Sdk $VersionGlob) -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -in @("$Name.bat", "$Name.exe", $Name) } |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($found) { return $found.FullName }
    return $null
}

if (-not (Test-Path $Apk)) { Write-Host "APK nao encontrado: $Apk (rode :app:assembleRelease)" -ForegroundColor Red; exit 1 }
$Apk = (Resolve-Path $Apk).Path

$apkanalyzer = Find-Tool "apkanalyzer" "cmdline-tools"
if (-not $apkanalyzer) { Write-Host "apkanalyzer nao encontrado em $Sdk\cmdline-tools" -ForegroundColor Red; exit 1 }

$failures = New-Object System.Collections.Generic.List[string]

# --- size / dex breakdown ----------------------------------------------------
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkFile = Get-Item $Apk
$zip = [IO.Compression.ZipFile]::OpenRead($Apk)
$dex = @($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' } | Sort-Object FullName)
$dexBytes = ($dex | Measure-Object -Property Length -Sum).Sum
Write-Host ("APK : {0:N0} bytes ({1:N2} MB)" -f $apkFile.Length, ($apkFile.Length / 1MB))
Write-Host ("dex : {0} arquivo(s), {1:N0} bytes ({2:N2} MB)" -f $dex.Count, $dexBytes, ($dexBytes / 1MB))
foreach ($entry in $dex) { Write-Host ("      {0,-14} {1,12:N0} bytes" -f $entry.FullName, $entry.Length) }
foreach ($entry in $zip.Entries | Where-Object { $_.FullName -eq 'resources.arsc' }) {
    Write-Host ("      {0,-14} {1,12:N0} bytes" -f $entry.FullName, $entry.Length)
}
$zip.Dispose()

# --- classes that must survive R8 -------------------------------------------
Write-Host "`nClasses obrigatorias (apkanalyzer dex packages --defined-only):"
$packages = & $apkanalyzer dex packages --defined-only $Apk 2>$null
if ($LASTEXITCODE -ne 0) { Write-Host "apkanalyzer falhou (exit $LASTEXITCODE)" -ForegroundColor Red; exit 1 }

# A class row looks like: "C d <methods> <fields> <bytes> <class name>".
$defined = @{}
foreach ($line in $packages) {
    if ($line -match '^C\s+d\s+\d+\s+\d+\s+\d+\s+(\S+)$') { $defined[$Matches[1]] = $true }
}
foreach ($className in $RequiredClasses) {
    if ($defined.ContainsKey($className)) {
        Write-Host ("  OK    {0}" -f $className) -ForegroundColor Green
    } else {
        Write-Host ("  FALTA {0}" -f $className) -ForegroundColor Red
        $failures.Add($className)
    }
}
Write-Host ("  ({0} classes do app definidas no APK)" -f @($defined.Keys | Where-Object { $_ -like 'com.anderson.onedrivesync.*' }).Count)

# --- resources that must survive shrinkResources -----------------------------
Write-Host "`nRecursos obrigatorios (aapt2 dump resources):"
$aapt2 = Find-Tool "aapt2" "build-tools"
if ($aapt2) {
    $table = & $aapt2 dump resources $Apk 2>$null
    $present = @{}
    foreach ($line in $table) {
        if ($line -match '^\s*resource 0x[0-9a-f]+ ([\w-]+)/([\w.-]+)') { $present["$($Matches[1])/$($Matches[2])"] = $true }
    }
    foreach ($res in $RequiredResources) {
        if ($present.ContainsKey($res)) {
            Write-Host ("  OK    {0}" -f $res) -ForegroundColor Green
        } else {
            Write-Host ("  FALTA {0}" -f $res) -ForegroundColor Red
            $failures.Add($res)
        }
    }
} else {
    Write-Host "  aapt2 nao encontrado em $Sdk\build-tools" -ForegroundColor Yellow
    $failures.Add("aapt2")
}

# --- signing certificate -----------------------------------------------------
Write-Host "`nCertificado:"
$apksigner = Find-Tool "apksigner" "build-tools"
if ($apksigner) {
    # apksigner writes JVM warnings to stderr; that must not abort the script under EAP=Stop.
    $ErrorActionPreference = "Continue"
    $cert = & $apksigner verify --print-certs $Apk 2>&1 | ForEach-Object { "$_" }
    $signerExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($signerExit -ne 0) {
        Write-Host ("  apksigner verify FALHOU (exit {0})" -f $signerExit) -ForegroundColor Red
        $cert | ForEach-Object { Write-Host "  $_" }
        $failures.Add("assinatura")
    } else {
        $cert | Where-Object { $_ -match 'certificate DN|SHA-256 digest' } | ForEach-Object { Write-Host "  $_" }
    }
} else {
    Write-Host "  apksigner nao encontrado em $Sdk\build-tools" -ForegroundColor Yellow
    $failures.Add("apksigner")
}

if ($failures.Count -gt 0) {
    Write-Host ("`nFALHOU: {0}" -f ($failures -join ", ")) -ForegroundColor Red
    exit 1
}
Write-Host "`nOK: o APK contem as classes e recursos criticos e esta assinado." -ForegroundColor Green
exit 0
