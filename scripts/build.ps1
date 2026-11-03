param(
    [ValidateSet("apk", "test", "all", "clean")][string]$Task = "all"
)

# JDK errado (25 do sistema) e o PATH desta máquina contém uma entrada com aspas soltas
# (Machine PATH: 'C:\Program Files\GitHub CLI"'). O AGP injeta o PATH em
# -Djava.library.path do worker de teste e o quoting quebra o CreateProcess no Windows.
# Corrigimos só neste processo.
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
$env:PATH = (($env:PATH -split ';') | Where-Object { $_ -and -not $_.Contains('"') }) -join ';'

$tasks = switch ($Task) {
    "apk" { @(":app:assembleDebug") }
    "test" { @(":app:testDebugUnitTest") }
    "clean" { @("clean") }
    default { @(":app:assembleDebug", ":app:testDebugUnitTest") }
}

& "$PSScriptRoot\..\gradlew.bat" @tasks --no-daemon
exit $LASTEXITCODE
