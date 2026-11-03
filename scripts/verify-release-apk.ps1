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

# Resources the app needs at runtime. `ic_notification` is only reached through a compile-time
# constant, so the shrinker cannot see the reference (see app/src/main/res/raw/keep.xml).
$RequiredResources = @(
    'drawable/ic_notification'
    'mipmap/ic_launcher'
    'style/Theme.OneDriveSync'
    'xml/file_paths'
    'string/app_name'
)

# Resources referenced through a `const val`. Checking the table is not enough: the entry can be
# there (tools:keep) while the code that posts the notification passes icon 0. The value is inlined
# by Kotlin, so it is verified in the dex, never in the R class.
$RequiredInlinedResources = @(
    'drawable/ic_notification'
)

function Find-Tool([string]$Name, [string]$VersionGlob) {
    $found = Get-ChildItem -Path (Join-Path $Sdk $VersionGlob) -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -in @("$Name.bat", "$Name.exe", $Name) } |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($found) { return $found.FullName }
    return $null
}

# A resource id is a 4-byte little-endian constant in the dex, so the raw bytes are what proves the
# drawable is still referenced by live code. Latin-1 maps one byte to one char, which makes the
# search a plain IndexOf instead of a byte-by-byte loop over a multi-megabyte dex.
function Test-ResourceIdInDex([System.IO.Compression.ZipArchive]$Archive, [uint32]$ResourceId) {
    $latin1 = [Text.Encoding]::GetEncoding(28591)
    $needle = $latin1.GetString([byte[]]@(
            [byte]($ResourceId -band 0xff),
            [byte](($ResourceId -shr 8) -band 0xff),
            [byte](($ResourceId -shr 16) -band 0xff),
            [byte](($ResourceId -shr 24) -band 0xff)
        ))
    foreach ($entry in $Archive.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' }) {
        $stream = $entry.Open()
        try {
            $memory = New-Object System.IO.MemoryStream
            $stream.CopyTo($memory)
            $bytes = $memory.ToArray()
        } finally {
            $stream.Dispose()
        }
        if ($latin1.GetString($bytes).IndexOf($needle, [StringComparison]::Ordinal) -ge 0) { return $true }
    }
    return $false
}

# AGP 9's compile-only R class declares the ids as non-final fields with `ConstantValue: 0`, and
# Kotlin folds `const val ICON = R.drawable.ic_notification` from that attribute: the literal
# 0x7f050007 is never emitted, in debug or release. The fallback proof is the defensive resolution
# in Notifier: the dex must look the drawable up by name instead of passing 0 to setSmallIcon.
function Test-NotifierResolvesIcon([string]$Analyzer, [string]$ApkPath) {
    $code = & $Analyzer dex code --class 'com.anderson.onedrivesync.notify.Notifier' $ApkPath 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $code) { return $false }
    $text = $code -join "`n"
    return $text.Contains('getIdentifier') -and $text.Contains('ic_notification')
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
Write-Host "`nRecursos obrigatorios (aapt2 dump resources + conteudo do APK):"
$aapt2 = Find-Tool "aapt2" "build-tools"
if ($aapt2) {
    # `aapt2 dump resources` prints the table entry and then one line per configuration:
    #   resource 0x7f050007 drawable/ic_notification
    #     () (file) res/vO.xml type=XML
    $table = & $aapt2 dump resources $Apk 2>$null
    $resourceIds = @{}
    $resourceFiles = @{}
    $current = $null
    foreach ($line in $table) {
        if ($line -match '^\s*resource 0x([0-9a-f]+) ([\w-]+)/([\w.-]+)') {
            $current = "$($Matches[2])/$($Matches[3])"
            $resourceIds[$current] = [Convert]::ToUInt32($Matches[1], 16)
            $resourceFiles[$current] = New-Object System.Collections.Generic.List[string]
        } elseif ($current -and $line -match '\(file\)\s+(\S+)') {
            $resourceFiles[$current].Add($Matches[1])
        }
    }
    $apkZip = [IO.Compression.ZipFile]::OpenRead($Apk)
    try {
        foreach ($res in $RequiredResources) {
            if (-not $resourceIds.ContainsKey($res)) {
                Write-Host ("  FALTA {0} (entrada ausente da tabela de recursos)" -f $res) -ForegroundColor Red
                $failures.Add($res)
                continue
            }
            if ($resourceFiles[$res].Count -eq 0) {
                Write-Host ("  OK    {0} (recurso de valores, sem arquivo)" -f $res) -ForegroundColor Green
                continue
            }
            $missing = @($resourceFiles[$res] | Where-Object { $null -eq $apkZip.GetEntry($_) })
            if ($missing.Count -gt 0) {
                Write-Host ("  FALTA {0}: arquivo(s) na tabela sem entrada no APK: {1}" -f $res, ($missing -join ', ')) -ForegroundColor Red
                $failures.Add("$res (arquivo)")
            } else {
                Write-Host ("  OK    {0} (arquivo {1})" -f $res, ($resourceFiles[$res] -join ', ')) -ForegroundColor Green
            }
        }
        foreach ($res in $RequiredInlinedResources) {
            if (-not $resourceIds.ContainsKey($res)) {
                Write-Host ("  FALTA {0} (sem id na tabela: nao ha como checar o dex)" -f $res) -ForegroundColor Red
                $failures.Add("$res (id)")
                continue
            }
            if (Test-ResourceIdInDex -Archive $apkZip -ResourceId $resourceIds[$res]) {
                Write-Host ("  OK    {0} (id 0x{1:x8} presente no classes.dex)" -f $res, $resourceIds[$res]) -ForegroundColor Green
            } elseif (Test-NotifierResolvesIcon -Analyzer $apkanalyzer -ApkPath $Apk) {
                Write-Host ("  OK    {0} (id nao e literal no dex; Notifier resolve por nome em runtime)" -f $res) -ForegroundColor Green
            } else {
                Write-Host ("  FALTA {0}: id 0x{1:x8} ausente do classes*.dex e sem resolucao defensiva em Notifier" -f $res, $resourceIds[$res]) -ForegroundColor Red
                $failures.Add("$res (id no dex)")
            }
        }
    } finally {
        $apkZip.Dispose()
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
