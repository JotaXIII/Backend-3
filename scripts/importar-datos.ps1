param([string]$Origen)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$Origen) {
    $Origen=Join-Path $root '.work/fin_legacy_data'
    if (!(Test-Path -LiteralPath $Origen)) {
        & git clone 'https://github.com/KariVillagran/fin_legacy_data.git' $Origen
        if ($LASTEXITCODE -ne 0) { throw 'No fue posible obtener los datos' }
    }
}
function Campo($fila,$alternativas) {
    foreach($nombre in $alternativas) {
        $prop=$fila.PSObject.Properties[$nombre]
        if ($null -ne $prop) { return $prop.Value }
    }
    throw "Columna requerida: $($alternativas -join ', ')"
}
$archivos=@(Get-ChildItem -LiteralPath $Origen -Recurse -Filter *.csv)
$transformados=@{}
foreach($archivo in $archivos) {
    $filas=@(Import-Csv -LiteralPath $archivo.FullName -Encoding UTF8)
    if (!$filas.Count) { continue }
    $columnas=@($filas[0].PSObject.Properties.Name)
    if ($columnas -contains 'saldo') {
        $transformados.intereses=@($filas|ForEach-Object { [ordered]@{
            cuenta_id=Campo $_ @('cuenta_id','cuentaId','id');nombre=Campo $_ @('nombre','titular');
            saldo=Campo $_ @('saldo');edad=Campo $_ @('edad');tipo=Campo $_ @('tipo','tipo_cuenta')
        }|ForEach-Object {[pscustomobject]$_} })
    } elseif ($columnas -contains 'descripcion') {
        $transformados.cuentas_anuales=@($filas|ForEach-Object { [pscustomobject][ordered]@{
            cuenta_id=Campo $_ @('cuenta_id','cuentaId');fecha=Campo $_ @('fecha');
            transaccion=Campo $_ @('transaccion','tipo','tipo_transaccion');monto=Campo $_ @('monto');descripcion=Campo $_ @('descripcion')
        } })
    } elseif ($columnas -contains 'monto') {
        $transformados.transacciones=@($filas|ForEach-Object { [pscustomobject][ordered]@{
            id=Campo $_ @('id','transaccion_id');fecha=Campo $_ @('fecha');monto=Campo $_ @('monto');tipo=Campo $_ @('tipo','tipo_transaccion')
        } })
    }
}
foreach($nombre in @('transacciones','intereses','cuentas_anuales')) {
    if (!$transformados.ContainsKey($nombre)) { throw "Datos ausentes: $nombre" }
}
$salida=Join-Path $root 'build/datos-importados'
New-Item -ItemType Directory -Path $salida -Force|Out-Null
foreach($nombre in $transformados.Keys) {
    $transformados[$nombre]|Export-Csv -LiteralPath (Join-Path $salida "$nombre.csv") -NoTypeInformation -Encoding UTF8
}
Write-Output $salida
