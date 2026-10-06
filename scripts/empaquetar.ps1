param([string]$Destino, [switch]$Actualizar)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $Destino) {
    $Destino = Join-Path (Split-Path $projectRoot -Parent) "Exp3_S8_JuanCarlos_Osega.zip"
}
if ((Test-Path -LiteralPath $Destino) -and -not $Actualizar) {
    throw "El archivo de destino ya existe: $Destino"
}
$stage = Join-Path ([IO.Path]::GetTempPath()) ("entrega-" + [Guid]::NewGuid().ToString("N"))
$folder = Join-Path $stage "Exp3_S8_JuanCarlos_Osega"
New-Item -ItemType Directory -Path $folder | Out-Null

# Paquete sin archivos temporales.
$files = git -C $projectRoot -c core.quotepath=false ls-files --cached --others --exclude-standard
if ($LASTEXITCODE -ne 0) { throw "No fue posible listar los archivos" }
foreach ($relative in $files) {
    $source = Join-Path $projectRoot $relative
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
    $target = Join-Path $folder $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Copy-Item -LiteralPath $source -Destination $target
}
Compress-Archive -LiteralPath $folder -DestinationPath $Destino -Force:$Actualizar
Write-Host "Entrega creada: $Destino"
