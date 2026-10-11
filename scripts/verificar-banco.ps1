param([string]$BaseUrl = '', [switch]$ProbarEscalabilidad, [int]$PortOffset = 0)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (Test-Path .env) {
    foreach ($linea in Get-Content .env) {
        if ($linea -match '^([A-Z_]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process') }
    }
}
$resultados = [Collections.Generic.List[object]]::new()
function Registrar($nombre,$datos) {
    $resultados.Add([ordered]@{ comprobacion=$nombre; resultado='CORRECTO'; datos=$datos })
    Write-Host "Correcto: $nombre"
}
function Url($canal,$ruta) {
    if ($BaseUrl) { return $BaseUrl.TrimEnd('/')+$ruta }
    $puerto = switch ($canal) { 'web' {8090} 'movil' {8091} 'cajeros' {8092} 'oauth' {9000} 'batch' {8080} }
    $puerto += $PortOffset
    return "http://127.0.0.1:$puerto$ruta"
}
function Token($cliente,$variable,$predeterminado,$scopes) {
    $secreto=[Environment]::GetEnvironmentVariable($variable)
    if (!$secreto) { $secreto=$predeterminado }
    $basic=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($cliente+':'+$secreto))
    $respuesta=Invoke-RestMethod -Method Post -Uri (Url oauth '/oauth2/token') -Headers @{Authorization="Basic $basic"} -Body @{grant_type='client_credentials';scope=$scopes}
    return @{Authorization='Bearer '+$respuesta.access_token}
}
function Api($canal,$metodo,$ruta,$headers,$body=$null) {
    $argumentos=@{Uri=(Url $canal $ruta);Method=$metodo;Headers=$headers;TimeoutSec=20}
    if ($null -ne $body) { $argumentos.ContentType='application/json';$argumentos.Body=($body|ConvertTo-Json -Depth 10) }
    try { return Invoke-RestMethod @argumentos }
    catch {
        if (!$_.Exception.Response) {
            Write-Warning ("Conexion fallida: {0} {1}" -f $metodo,$argumentos.Uri)
            Write-Warning $_.ScriptStackTrace
        }
        throw
    }
}
function EsperarApi($canal,$ruta,$headers) {
    for($i=0;$i -lt 60;$i++) {
        try { return Api $canal Get $ruta $headers } catch { if($i -eq 59) { throw };Start-Sleep -Seconds 1 }
    }
}
function Exigir($condicion,$mensaje) { if (!$condicion) { throw $mensaje } }
function ExigirEstado($canal,$metodo,$ruta,$headers,$esperado,$body=$null) {
    try {
        $respuesta=Api $canal $metodo $ruta $headers $body
        $detalle = if ($respuesta.estado) { "estado=$($respuesta.estado)" } else { 'respuesta exitosa' }
        throw "HTTP $esperado esperado en $metodo $ruta; recibido $detalle"
    }
    catch {
        if (!$_.Exception.Response) { throw }
        $codigo=[int]$_.Exception.Response.StatusCode
        Exigir ($codigo -eq $esperado) "HTTP $codigo; esperado $esperado"
        Registrar "HTTP $esperado $ruta" @{codigo=$codigo}
    }
}
function Cuenta($headers) {
    $resumen=EsperarApi web '/api/web/resumen' $headers
    $activa=@($resumen.cuentas | Where-Object estado -eq 'ACTIVA' | Select-Object -First 1)
    if ($activa.Count) { return [long]$activa[0].id }
    return [long](Api web Post '/api/web/cuentas' $headers @{tipo='AHORRO'}).id
}
function Saldo($headers,$id) {
    $resumen=Api web Get '/api/web/resumen' $headers
    return [decimal]($resumen.cuentas | Where-Object id -eq $id).saldo
}
function Completar($headers,$datos) {
    $resultado=Api web Post '/api/web/pagos' $headers $datos
    for($i=0;$i -lt 60 -and $resultado.estado -eq 'PENDIENTE';$i++) {
        Start-Sleep -Seconds 1;$resultado=Api web Get ('/api/web/pagos/'+$datos.id) $headers
    }
    Exigir ($resultado.estado -eq 'COMPLETADO') 'Pago sin completar'
    return $resultado
}
$web=Token web-demo OAUTH_WEB_SECRET web-local 'datos.read pagos.write cuentas.write clientes.write'
$movil=Token movil-demo OAUTH_MOVIL_SECRET movil-local 'datos.read pagos.write'
$cajero=Token cajeros-demo OAUTH_CAJEROS_SECRET cajeros-local 'datos.read retiros.write'
$otro=Token otro-web OAUTH_OTRO_SECRET otro-local 'datos.read pagos.write cuentas.write clientes.write'
$operador=Token operador OAUTH_CLIENT_SECRET operador-local 'datos.read procesos.write'
ExigirEstado web Get '/api/web/resumen' @{} 401
ExigirEstado movil Get '/api/movil/resumen' $web 403
ExigirEstado web Post '/api/web/cuentas' $movil 403 @{tipo='AHORRO'}
$origen=Cuenta $web;$destino=Cuenta $otro
$prefijo=[Guid]::NewGuid().ToString('N')
$null=Completar $web @{id=$prefijo+'d';tipo='DEPOSITO';destino=$origen;monto=1000}
$inicial=Saldo $web $origen;$otroInicial=Saldo $otro $destino
$pago=@{id=$prefijo+'t';tipo='TRANSFERENCIA';origen=$origen;destino=$destino;monto=200}
$null=Completar $web $pago
$null=Completar $web $pago
Exigir ((Saldo $web $origen) -eq ($inicial-200)) 'Debito duplicado'
Exigir ((Saldo $otro $destino) -eq ($otroInicial+200)) 'Credito incorrecto'
Registrar 'Transferencia atomica e idempotente' @{id=$pago.id;monto=200}
$resumenWeb=Api web Get '/api/web/resumen' $web
$resumenMovil=Api movil Get '/api/movil/resumen' $movil
Exigir ($resumenWeb.cliente.email -and !$resumenMovil.cliente -and !$resumenMovil.cuentas[0].cliente_id) 'Contrato BFF incorrecto'
Registrar 'Contratos BFF' @{bytesWeb=($resumenWeb|ConvertTo-Json -Compress -Depth 10).Length;bytesMovil=($resumenMovil|ConvertTo-Json -Compress -Depth 10).Length}
$retiro=@{id=$prefijo+'r';tipo='RETIRO';origen=$origen;monto=50}
$resultado=Api cajeros Post '/api/cajeros/retiros' $cajero $retiro
for($i=0;$i -lt 60 -and $resultado.estado -eq 'PENDIENTE';$i++) {
    Start-Sleep -Seconds 1;$resultado=Api cajeros Get ('/api/cajeros/retiros/'+$retiro.id) $cajero
}
Exigir ($resultado.estado -eq 'COMPLETADO') 'Retiro sin completar'
$saldo=Api cajeros Get "/api/cajeros/cuentas/$origen/saldo" $cajero
Exigir ([decimal]$saldo.saldo -eq ($inicial-250)) 'Saldo del cajero incorrecto'
Registrar 'Retiro autorizado' @{id=$retiro.id;monto=50}
ExigirEstado web Get ('/api/web/pagos/'+$pago.id) $otro 404
$alterado=@{id=$pago.id;tipo='TRANSFERENCIA';origen=$origen;destino=$destino;monto=201}
ExigirEstado web Post '/api/web/pagos' $web 409 $alterado
$rechazo=@{id=$prefijo+'x';tipo='PAGO';origen=$origen;monto=999999999999}
ExigirEstado web Post '/api/web/pagos' $web 409 $rechazo
for($i=0;$i -lt 60;$i++) {
    $sql="SELECT COUNT(*) FROM pagos.eventos_recibidos WHERE id='$($pago.id)-COMPLETADO';"
    $cantidad=& docker compose exec -T postgres psql -U banco -d banco_xyz -tAc $sql
    if ($LASTEXITCODE -ne 0) { throw 'Consulta de eventos fallida' }
    if ([int]($cantidad|Select-Object -Last 1) -ge 1) { break }
    Start-Sleep -Seconds 1
}
Exigir ([int]($cantidad|Select-Object -Last 1) -eq 1) 'Evento Kafka no recibido'
Registrar 'Kafka y deduplicacion' @{id=$pago.id;eventos=1}
if (!$BaseUrl) {
    foreach($proceso in @('transacciones','intereses','estados-anuales')) {
        $solicitud=Api batch Post "/api/procesos/$proceso" $operador
        for($i=0;$i -lt 90;$i++) {
            $estado=Api batch Get ('/api/solicitudes/'+$solicitud.id) $operador
            if ($estado.estado -in @('COMPLETED','FAILED')) { break };Start-Sleep -Seconds 1
        }
        Exigir ($estado.estado -eq 'COMPLETED') "Batch $proceso fallido"
        Registrar "Batch $proceso" @{id=$solicitud.id;estado=$estado.estado}
    }
}
if ($ProbarEscalabilidad) {
    & docker compose up -d --scale clientes=2 --scale cuentas=2 --scale pagos=2 --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw 'Escalado fallido' }
    $null=EsperarApi web '/api/web/resumen' $web
    Registrar 'Escalabilidad horizontal' @{clientes=2;cuentas=2;pagos=2}
}
New-Item -ItemType Directory -Path docs/evidencia -Force|Out-Null
[ordered]@{fecha=(Get-Date).ToString('o');comprobaciones=$resultados}|ConvertTo-Json -Depth 12|Set-Content docs/evidencia/verificacion-banco.json -Encoding UTF8
Write-Output "$($resultados.Count) comprobaciones correctas"
