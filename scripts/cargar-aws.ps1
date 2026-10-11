param([string]$Ruta = (Join-Path (Split-Path $PSScriptRoot -Parent) '.env.aws'))
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $Ruta)) { throw 'No existe el archivo de credenciales AWS.' }
$permitidas = @('AWS_ACCESS_KEY_ID','AWS_SECRET_ACCESS_KEY','AWS_SESSION_TOKEN','AWS_REGION','AWS_DEFAULT_REGION')
$valores = @{}
foreach ($linea in Get-Content -LiteralPath $Ruta) {
    $texto = $linea.Trim()
    if (!$texto -or $texto.StartsWith('#') -or $texto.StartsWith('[')) { continue }
    $partes = $texto.Split('=',2)
    if ($partes.Count -ne 2) { throw 'Formato de credenciales invalido.' }
    $nombre = $partes[0].Trim().ToUpperInvariant()
    if ($nombre -notin $permitidas) { throw 'Variable AWS no admitida.' }
    $valor = $partes[1].Trim().Trim('"').Trim("'")
    if (!$valor) { throw 'Hay una variable AWS vacia.' }
    $valores[$nombre] = $valor
}
foreach ($nombre in @('AWS_ACCESS_KEY_ID','AWS_SECRET_ACCESS_KEY','AWS_SESSION_TOKEN')) {
    if (!$valores.ContainsKey($nombre)) { throw 'Falta una credencial temporal AWS requerida.' }
}
foreach ($nombre in $valores.Keys) { [Environment]::SetEnvironmentVariable($nombre,$valores[$nombre],'Process') }
if ($valores.ContainsKey('AWS_REGION')) {
    [Environment]::SetEnvironmentVariable('TF_VAR_region',$valores['AWS_REGION'],'Process')
} elseif ($valores.ContainsKey('AWS_DEFAULT_REGION')) {
    [Environment]::SetEnvironmentVariable('TF_VAR_region',$valores['AWS_DEFAULT_REGION'],'Process')
}
Write-Host 'Credenciales temporales cargadas en el proceso actual.'
