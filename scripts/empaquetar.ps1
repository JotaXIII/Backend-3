param([string]$Destino, [switch]$Actualizar)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $Destino) {
    $Destino = Join-Path (Split-Path $projectRoot -Parent) "EFT_S9_JuanCarlos_Osega.zip"
}
if ((Test-Path -LiteralPath $Destino) -and -not $Actualizar) {
    throw "El archivo de destino ya existe: $Destino"
}
$stage = Join-Path ([IO.Path]::GetTempPath()) ("entrega-" + [Guid]::NewGuid().ToString("N"))
$folder = Join-Path $stage "EFT_S9_JuanCarlos_Osega"
New-Item -ItemType Directory -Path $folder | Out-Null

# Paquete sin archivos temporales.
$files = git -C $projectRoot -c core.quotepath=false ls-files --cached --others --exclude-standard
if ($LASTEXITCODE -ne 0) { throw "No fue posible listar los archivos" }
foreach ($relative in $files) {
    if ($relative -match '(^|/)\.env($|\.)' -and $relative -notmatch '\.example$') { continue }
    if ($relative -match '(^|/)(build|\.terraform|\.git)/|\.(tfstate|tfplan|pem|p12)($|\.)') { continue }
    $source = Join-Path $projectRoot $relative
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
    $target = Join-Path $folder $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Copy-Item -LiteralPath $source -Destination $target
}
$python = Get-Command python -ErrorAction Stop
$codigoZip = @'
from pathlib import Path
import sys, zipfile
root = Path(sys.argv[1])
destination = Path(sys.argv[2])
with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as archive:
    for source in sorted(root.rglob('*')):
        relative = source.relative_to(root.parent).as_posix()
        if source.is_dir():
            info = zipfile.ZipInfo(relative + '/')
            info.create_system = 3
            info.external_attr = (0o40755 << 16) | 0x10
            archive.writestr(info, b'')
        elif source.is_file():
            info = zipfile.ZipInfo(relative)
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, source.read_bytes())
'@
$codigoZip | & $python.Source - $folder $Destino
if ($LASTEXITCODE -ne 0) { throw 'No fue posible crear el ZIP.' }
Write-Host "Entrega creada: $Destino"
