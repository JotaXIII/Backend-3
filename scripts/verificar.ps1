param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$AuthUrl = "http://localhost:9000",
    [switch]$ProbarInterrupciones
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$composeFile = Join-Path $projectRoot "docker-compose.yaml"
$evidence = [System.Collections.Generic.List[object]]::new()

function Registrar($caso, $resultado) {
    $evidence.Add([ordered]@{ caso = $caso; resultado = $resultado })
    Write-Host "$caso : $resultado"
}

function Verificar($condicion, $mensaje) {
    if (-not $condicion) { throw $mensaje }
}

function EstadoHttp($url, $headers = @{}, $method = "GET") {
    try {
        $response = Invoke-WebRequest -Uri $url -Headers $headers -Method $method -UseBasicParsing
        return [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        throw
    }
}

function ObtenerToken($cliente, $secret, $scope) {
    $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${cliente}:${secret}"))
    $result = Invoke-RestMethod -Uri "$AuthUrl/oauth2/token" -Method Post `
        -Headers @{ Authorization = "Basic $basic" } `
        -Body @{ grant_type = "client_credentials"; scope = $scope }
    return @{ Authorization = "Bearer $($result.access_token)" }
}

function EsperarSolicitud($id, $headers) {
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        $result = Invoke-RestMethod "$BaseUrl/api/solicitudes/$id" -Headers $headers
        if ($result.estado -eq "COMPLETED") { return $result }
        Verificar ($result.estado -ne "FAILED") "Proceso fallido: $id"
        Start-Sleep -Seconds 1
    }
    throw "Solicitud sin resultado: $id"
}

$operatorSecret = if ($env:OAUTH_CLIENT_SECRET) { $env:OAUTH_CLIENT_SECRET } else { "operador-local" }
$readerSecret = if ($env:OAUTH_READER_SECRET) { $env:OAUTH_READER_SECRET } else { "lector-local" }
foreach ($url in @("$AuthUrl/actuator/health", "$BaseUrl/actuator/health")) {
    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        try {
            $health = Invoke-RestMethod $url -TimeoutSec 5
            if ($health.status -eq "UP") { $ready = $true; break }
        } catch {
        }
        Start-Sleep -Seconds 1
    }
    Verificar $ready "Servicio sin disponibilidad: $url"
}
$headers = ObtenerToken "operador" $operatorSecret "datos.read procesos.write"
$reader = ObtenerToken "lector" $readerSecret "datos.read"

$code = EstadoHttp "$BaseUrl/api/resiliencia"
Verificar ($code -eq 401) "El acceso sin token debe devolver 401"
Registrar "Sin token" $code
$code = EstadoHttp "$BaseUrl/api/resiliencia" @{ Authorization = ($headers.Authorization + "alterado") }
Verificar ($code -eq 401) "El token alterado debe devolver 401"
Registrar "Firma invalida" $code
$code = EstadoHttp "$BaseUrl/api/procesos/transacciones" $reader "POST"
Verificar ($code -eq 403) "La escritura sin permiso debe devolver 403"
Registrar "Permiso insuficiente" $code
$code = EstadoHttp "$BaseUrl/api/procesos/desconocido" $headers "POST"
Verificar ($code -eq 400) "El proceso desconocido debe devolver 400"
Registrar "Proceso invalido" $code

$ids = @{}
foreach ($process in @("transacciones", "intereses", "estados-anuales")) {
    $request = Invoke-RestMethod "$BaseUrl/api/procesos/$process" -Headers $headers -Method Post
    $ids[$process] = $request.id
    $result = EsperarSolicitud $request.id $headers
    Registrar "JMS $process" $result.estado
}
$code = EstadoHttp "$BaseUrl/api/solicitudes/$($ids.transacciones)" $reader
Verificar ($code -eq 404) "Las solicitudes deben pertenecer al cliente autenticado"
Registrar "Propietario distinto" $code

foreach ($item in @(@("transacciones", 9), @("intereses", 4), @("estados-anuales", 7))) {
    $result = $null
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        $result = Invoke-RestMethod "$BaseUrl/api/consultas/$($item[0])" -Headers $headers
        if ($result.disponible) { break }
        Start-Sleep -Seconds 1
    }
    Verificar $result.disponible "Consulta sin respuesta"
    Verificar (@($result.datos).Count -eq $item[1]) "Cantidad inesperada para $($item[0])"
    Registrar "Resultados $($item[0])" @($result.datos).Count
}

$id = $ids.transacciones
docker compose -f $composeFile exec -T postgres psql -U banco -d banco_xyz -c `
    "UPDATE solicitudes SET estado = 'PENDIENTE' WHERE id = '$id';" | Out-Null
Verificar ($LASTEXITCODE -eq 0) "No fue posible preparar la repeticion"
$null = EsperarSolicitud $id $headers
$count = docker compose -f $composeFile exec -T postgres psql -U banco -d banco_xyz -tAc `
    "SELECT COUNT(*) FROM batch_job_execution_params WHERE parameter_name='solicitud.id' AND parameter_value='$id';"
Verificar ($LASTEXITCODE -eq 0 -and [int]("$count".Trim()) -eq 1) "La solicitud duplicada se ejecuto mas de una vez"
Registrar "Solicitud repetida" "Una ejecucion"

if ($ProbarInterrupciones) {
    try {
        docker compose -f $composeFile stop consultas | Out-Null
        Verificar ($LASTEXITCODE -eq 0) "No fue posible detener consultas"
        for ($attempt = 0; $attempt -lt 6; $attempt++) {
            $result = Invoke-RestMethod "$BaseUrl/api/consultas/transacciones" -Headers $headers
            Verificar (-not $result.disponible) "No se activo la respuesta alternativa"
        }
        $state = Invoke-RestMethod "$BaseUrl/api/resiliencia" -Headers $headers
        Verificar ($state.circuito -eq "OPEN") "El circuito no se abrio"
        Registrar "Servicio detenido" "Fallback y circuito OPEN"
    } finally {
        docker compose -f $composeFile start consultas | Out-Null
    }
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        $result = Invoke-RestMethod "$BaseUrl/api/consultas/transacciones" -Headers $headers
        if ($result.disponible) { break }
        Start-Sleep -Seconds 1
    }
    Verificar $result.disponible "La consulta no se recupero"
    $null = Invoke-RestMethod "$BaseUrl/api/consultas/transacciones" -Headers $headers
    $state = Invoke-RestMethod "$BaseUrl/api/resiliencia" -Headers $headers
    Verificar ($state.circuito -eq "CLOSED") "El circuito no se cerro"
    Registrar "Servicio recuperado" $state.circuito

    try {
        docker compose -f $composeFile stop mensajeria | Out-Null
        Verificar ($LASTEXITCODE -eq 0) "No fue posible detener mensajeria"
        $request = Invoke-RestMethod "$BaseUrl/api/procesos/intereses" -Headers $headers -Method Post
        $result = Invoke-RestMethod "$BaseUrl/api/solicitudes/$($request.id)" -Headers $headers
        Verificar ($result.estado -eq "PENDIENTE") "La solicitud no quedo persistida"
        Registrar "Broker detenido" "Solicitud PENDIENTE"
    } finally {
        docker compose -f $composeFile start mensajeria | Out-Null
    }
    $result = EsperarSolicitud $request.id $headers
    Registrar "Broker recuperado" $result.estado
}

docker compose -f $composeFile up -d --wait --wait-timeout 180 | Out-Null
Verificar ($LASTEXITCODE -eq 0) "Los contenedores no recuperaron su salud"
$services = docker compose -f $composeFile ps --format json
Verificar ($LASTEXITCODE -eq 0) "No fue posible consultar los contenedores"
$directory = Join-Path $projectRoot "docs/evidencia"
New-Item -ItemType Directory -Force $directory | Out-Null
[ordered]@{
    fecha = (Get-Date).ToString("o")
    verificaciones = $evidence
    contenedores = @($services | ForEach-Object { ConvertFrom-Json $_ } |
        Select-Object Service,Image,State,Health,ID,CreatedAt,Ports)
} | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 (Join-Path $directory "verificacion-cloud.json")
Write-Host "Verificacion finalizada."
