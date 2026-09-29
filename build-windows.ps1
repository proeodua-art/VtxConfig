$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Out = Join-Path $Root 'out-win'
$Dist = Join-Path $Root 'dist-win'
Remove-Item $Out -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $Dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $Out,$Dist | Out-Null

$java = Get-Command java -ErrorAction Stop
$javac = Get-Command javac -ErrorAction Stop
$jpackage = Get-Command jpackage -ErrorAction Stop

$version = (& java -version 2>&1 | Select-String 'version').ToString()
Write-Host "Using $version"

javac -d $Out (Join-Path $Root 'src\VtxApp.java')
jar --create --file (Join-Path $Out 'VtxConfig.jar') --main-class VtxApp -C $Out .

jpackage --type msi `
  --name VtxConfig `
  --input $Out `
  --main-jar VtxConfig.jar `
  --dest $Dist `
  --app-version 1.0.0 `
  --win-shortcut `
  --win-menu `
  --win-dir-chooser `
  --java-options '-Xmx256m'

Write-Host "Created Windows installer in $Dist"
