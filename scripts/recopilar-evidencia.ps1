param()

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$composeFile = Join-Path $projectRoot "docker-compose.yaml"
$directory = Join-Path $projectRoot "docs/evidencia"
$lines = [System.Collections.Generic.List[string]]::new()
$verification = Get-Content -LiteralPath (Join-Path $directory "verificacion-cloud.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$tests = Get-Content -LiteralPath (Join-Path $directory "pruebas.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$services = @(docker compose -f $composeFile ps --format json | ForEach-Object { ConvertFrom-Json $_ })
if ($LASTEXITCODE -ne 0 -or $services.Count -ne 8) { throw "Se requieren los ocho componentes" }
if (@($services | Where-Object { $_.Health -ne "healthy" }).Count -ne 0) {
    throw "Hay componentes sin salud confirmada"
}

$secret = if ($env:OAUTH_CLIENT_SECRET) { $env:OAUTH_CLIENT_SECRET } else { "operador-local" }
$issuer = if ($env:OAUTH_ISSUER) { $env:OAUTH_ISSUER } else { "http://localhost:9000" }
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("operador:" + $secret))
$token = Invoke-RestMethod -Method Post -Uri "$issuer/oauth2/token" -Headers @{
    Authorization = "Basic $basic"
} -Body @{ grant_type = "client_credentials"; scope = "datos.read procesos.write" }
$oauth = [ordered]@{
    grant_type = "client_credentials"
    token_type = $token.token_type
    expires_in = $token.expires_in
    scope = $token.scope
}
$branch = git -C $projectRoot branch --show-current
$logs = @(docker compose -f $composeFile logs --no-color --tail 300 autorizacion configuracion descubrimiento batch consultas solicitudes)
if ($LASTEXITCODE -ne 0) { throw "No fue posible consultar los logs" }
$startup = @($logs | Where-Object { $_ -match 'Started (Autorizacion|Configuracion|Descubrimiento|BancoXyzBatch|Consultas|Solicitudes)Application' })
if ($startup.Count -lt 6) { throw "Falta evidencia de inicio de los servicios" }
$jobs = @($logs | Where-Object { $_ -match 'Job finalizado:.*estado=COMPLETED' })

$sql = Get-Content -LiteralPath (Join-Path $projectRoot "docs/consultas_evidencia.sql") -Raw -Encoding UTF8
$results = @($sql | docker compose -f $composeFile exec -T postgres psql -X -v ON_ERROR_STOP=1 -P pager=off -U banco -d banco_xyz)
if ($LASTEXITCODE -ne 0) { throw "No fue posible verificar la persistencia" }
$images = @(docker compose -f $composeFile images)
if ($LASTEXITCODE -ne 0) { throw "No fue posible listar las imagenes" }

# Evidencia sin secretos.
$lines.Add("EVIDENCIA DE EJECUCION - SEMANA 8")
$lines.Add("Rama: $branch")
$lines.Add("OAuth2: client_credentials | Bearer | scope=$($token.scope) | expires_in=$($token.expires_in)")
$lines.Add("")
$lines.Add("VERIFICACIONES FUNCIONALES")
foreach ($case in $verification.verificaciones) {
    $lines.Add("$($case.caso) : $($case.resultado)")
}
$lines.Add("")
$lines.Add("COMPONENTES")
foreach ($service in $services) {
    $lines.Add("$($service.Service) | $($service.Image) | $($service.Health)")
}
$lines.Add("")
$lines.Add("IMAGENES")
foreach ($line in $images) { $lines.Add($line) }
$lines.Add("")
$lines.Add("INICIO DE SERVICIOS")
foreach ($line in $startup) { $lines.Add($line) }
$lines.Add("")
$lines.Add("RESULTADOS DEL PROCESADOR")
foreach ($line in $jobs) { $lines.Add($line) }
$lines.Add("")
$lines.Add("PRUEBAS: $($tests.pruebas) | fallos=$($tests.fallos) | errores=$($tests.errores)")
$lines.Add("")
$lines.Add("PERSISTENCIA")
foreach ($line in $results) { $lines.Add($line) }
while ($lines.Count -gt 0 -and [string]::IsNullOrWhiteSpace($lines[$lines.Count - 1])) {
    $lines.RemoveAt($lines.Count - 1)
}
$lines | ForEach-Object { $_.TrimEnd() } |
    Set-Content -Encoding UTF8 (Join-Path $directory "salidas-cloud.txt")

[ordered]@{
    fecha = (Get-Date).ToString("o")
    rama = $branch
    oauth = $oauth
    componentes = @($services | Select-Object Service,Image,Health,State)
    inicio_servicios = $startup
    procesos = $jobs
    imagenes = $images
    consultas_sql = $results
} | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $directory "evidencia-operativa.json")
Write-Host "Evidencia actualizada sin tokens ni contrasenas."
