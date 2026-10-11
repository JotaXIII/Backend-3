param([int]$DuracionSegundos = 180, [int]$IntervaloSegundos = 5, [switch]$DosReplicas, [switch]$Iniciar)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if ($DuracionSegundos -lt 30 -or $IntervaloSegundos -lt 1) { throw 'Duracion minima 30 s e intervalo minimo 1 s.' }
$motor = docker info --format '{{json .}}' 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Sin acceso al motor Docker. No se registraron mediciones.' }
$info = $motor | ConvertFrom-Json
if ($Iniciar) {
    $argumentos = @('compose','-f','docker-compose.yaml','-f','docker-compose.medicion.yaml','up','-d','--build','--wait','--wait-timeout','600')
    if ($DosReplicas) { $argumentos += @('--scale','clientes=2','--scale','cuentas=2','--scale','pagos=2') }
    & docker @argumentos
    if ($LASTEXITCODE -ne 0) { throw 'Fallo el inicio de contenedores.' }
} elseif ($DosReplicas) {
    throw 'Usar -Iniciar para configurar dos replicas.'
}
$ids = @(docker compose ps -q)
if ($LASTEXITCODE -ne 0 -or $ids.Count -eq 0) { throw 'No hay contenedores del proyecto en ejecucion.' }
$muestras = [Collections.Generic.List[object]]::new()
$trabajo = Start-Job -ArgumentList (Join-Path $PSScriptRoot 'verificar-banco.ps1') -ScriptBlock {
    param($ruta)
    & $ruta -PortOffset 10000
}
$inicio = Get-Date
$errorInformado = $false
try {
    while (((Get-Date) - $inicio).TotalSeconds -lt $DuracionSegundos) {
        $lineas = @(& docker stats --no-stream --format '{{json .}}' @ids)
        if ($LASTEXITCODE -ne 0) { throw 'Fallo la lectura de estadisticas Docker.' }
        $muestras.Add([ordered]@{fecha=(Get-Date).ToUniversalTime().ToString('o');contenedores=@($lineas | ForEach-Object { $_ | ConvertFrom-Json })})
        Write-Host ('Muestra {0}: {1} contenedores; verificacion {2}' -f $muestras.Count,$ids.Count,$trabajo.State)
        if ($trabajo.State -eq 'Failed' -and !$errorInformado) {
            $errorInformado = $true
            $detalle = $trabajo.ChildJobs[0].JobStateInfo.Reason
            Write-Warning ("Verificacion fallida: " + $detalle)
        }
        Start-Sleep -Seconds $IntervaloSegundos
    }
    $final = @(& docker inspect @ids) | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Fallo la inspeccion final.' }
    $estado = $trabajo.State.ToString()
    $salida = @()
    if ($estado -eq 'Completed') { $salida = @(Receive-Job $trabajo -ErrorAction Stop) }
    $detalleError = $null
    if ($estado -eq 'Failed') {
        $detalleError = [ordered]@{
            motivo = [string]$trabajo.ChildJobs[0].JobStateInfo.Reason
            errores = @($trabajo.ChildJobs[0].Error | ForEach-Object { [string]$_ })
        }
    }
    $resultado = [ordered]@{
        tipo='MEDICION_LOCAL';fecha=$inicio.ToUniversalTime().ToString('o');motor_memoria_bytes=$info.MemTotal;motor_cpu=$info.NCPU
        verificacion_estado=$estado;verificacion_salida=$salida;verificacion_error=$detalleError;muestras=$muestras
        estado_final=@($final | ForEach-Object { [ordered]@{nombre=$_.Name;reinicios=$_.RestartCount;oom=$_.State.OOMKilled;salud=$_.State.Health.Status;limite_bytes=$_.HostConfig.Memory} })
        limites='La verificacion ejecuta operaciones funcionales y batch; no simula volumen bancario ni acredita rendimiento de EC2. Solo se incluyen contenedores del proyecto sin Caddy AWS.'
    }
    $ruta = 'docs/evidencia/recursos-local-'+$inicio.ToString('yyyyMMdd-HHmmss')+'.json'
    $resultado | ConvertTo-Json -Depth 15 | Set-Content -Encoding UTF8 $ruta
    Write-Host "Resultado: $ruta"
    if ($estado -ne 'Completed') { Write-Warning 'La verificacion no termino correctamente dentro de la ventana; ampliar duracion o revisar errores.' }
} finally {
    if ($trabajo.State -eq 'Running') { Stop-Job $trabajo }
    Remove-Job $trabajo -Force
}
