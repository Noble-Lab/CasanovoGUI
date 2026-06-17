# ============================================================
#  Casanovo GUI - build a self-contained Windows app with jpackage.
#  CI-friendly twin of build-exe.bat (no hardcoded paths: uses mvn and
#  jpackage from PATH, or $env:JAVA_HOME). App-image by default;
#  -Installer adds a .msi (requires the WiX Toolset).
#
#    pwsh ./packaging/package.ps1              -> dist\CasanovoGUI\CasanovoGUI.exe
#    pwsh ./packaging/package.ps1 -Installer   -> also dist\CasanovoGUI-<ver>.msi
# ============================================================
param([switch]$Installer)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)   # this script lives in packaging\; operate from the repo root

$App = 'CasanovoGUI'
$MainClass = 'org.casanovo.gui.CasanovoGuiApp'
$jpackage = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\jpackage.exe' } else { 'jpackage' }
$javaBin  = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { Split-Path (Get-Command java).Source }

Write-Host '[1/4] Building the fat JAR with Maven...'
mvn -q -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
$jar = Get-ChildItem target\casanovo-gui-*.jar | Where-Object { $_.Name -notmatch 'original|shaded' } | Select-Object -First 1

Write-Host '[2/4] Staging...'
Remove-Item -Recurse -Force staging, dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force staging | Out-Null
Copy-Item $jar.FullName staging\

$iconArg = @()
if (Test-Path 'packaging\icon.ico') { $iconArg = @('--icon', 'packaging\icon.ico') }

Write-Host '[3/4] Running jpackage (app-image)...'
& $jpackage --type app-image --name $App --input staging --main-jar $jar.Name --main-class $MainClass --java-options "--enable-native-access=ALL-UNNAMED" --dest dist @iconArg
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }

Write-Host '[4/4] Adding a Java launcher to the bundled runtime (needed to open PDV)...'
# jpackage strips java.exe/javaw.exe from the runtime; "Open in PDV" spawns a separate
# `java -jar PDV.jar` process that needs them. The trimmed runtime keeps the core libs
# and full module set, so the matching launchers from this JDK work.
Copy-Item (Join-Path $javaBin 'java.exe')  "dist\$App\runtime\bin\" -Force
Copy-Item (Join-Path $javaBin 'javaw.exe') "dist\$App\runtime\bin\" -Force

if ($Installer) {
  Write-Host '[+] Building .msi from the app-image (requires WiX)...'
  & $jpackage --type msi --app-image "dist\$App" --name $App --dest dist
}

Write-Host "Done. App: dist\$App\$App.exe"
