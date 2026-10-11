param([switch]$ForzarTerraformAmd64)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$raiz = Split-Path $PSScriptRoot -Parent
$descargas = Join-Path $raiz 'build/herramientas'
New-Item -ItemType Directory -Force -Path $descargas | Out-Null
if ($ForzarTerraformAmd64 -or !(Get-Command terraform -ErrorAction SilentlyContinue)) {
    Write-Host 'Descargando Terraform desde HashiCorp...'
    $indice = Invoke-RestMethod 'https://releases.hashicorp.com/terraform/index.json'
    $versiones = @($indice.versions.PSObject.Properties | Where-Object { $_.Name -match '^1\.[0-9]+\.[0-9]+$' } | Sort-Object { [version]$_.Name } -Descending)
    $version = $versiones[0].Name
    $release = $versiones[0].Value
    $arquitectura = 'amd64'
    $binario = @($release.builds | Where-Object { $_.os -eq 'windows' -and $_.arch -eq $arquitectura })
    if ($binario.Count -ne 1) { throw 'No se encontro un binario Terraform compatible.' }
    $nombre = "terraform_${version}_windows_${arquitectura}.zip"
    $archivo = Join-Path $descargas $nombre
    Invoke-WebRequest -UseBasicParsing -Uri $binario[0].url -OutFile $archivo
    $sumas = (Invoke-WebRequest -UseBasicParsing -Uri "https://releases.hashicorp.com/terraform/$version/terraform_${version}_SHA256SUMS").Content
    $linea = @($sumas -split "`n" | Where-Object { $_.Trim().EndsWith($nombre) })
    if ($linea.Count -ne 1) { throw 'Checksum Terraform no disponible.' }
    $esperado = ($linea[0].Trim() -split '\s+')[0]
    if ((Get-FileHash $archivo -Algorithm SHA256).Hash -ine $esperado) { throw 'Checksum Terraform incorrecto.' }
    $destino = Join-Path $descargas 'terraform'
    Expand-Archive -LiteralPath $archivo -DestinationPath $destino -Force
    $env:PATH = $destino + ';' + $env:PATH
    $rutaUsuario = [Environment]::GetEnvironmentVariable('Path','User')
    if ($destino -notin ($rutaUsuario -split ';')) { [Environment]::SetEnvironmentVariable('Path',($destino+';'+$rutaUsuario),'User') }
}
if (!(Get-Command aws -ErrorAction SilentlyContinue)) {
    Write-Host 'Descargando AWS CLI para el usuario actual...'
    $msi = Join-Path $descargas 'AWSCLIV2-User.msi'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://awscli.amazonaws.com/AWSCLIV2-User.msi' -OutFile $msi
    $firma = Get-AuthenticodeSignature -LiteralPath $msi
    if ($firma.Status -ne 'Valid' -or $firma.SignerCertificate.Subject -notmatch 'Amazon') { throw 'Firma del instalador AWS no valida.' }
    $proceso = Start-Process msiexec.exe -ArgumentList @('/i',('"'+$msi+'"'),'/qn','/norestart') -WindowStyle Hidden -Wait -PassThru
    if ($proceso.ExitCode -notin @(0,3010)) { throw "Instalacion AWS CLI fallida: $($proceso.ExitCode)" }
    $env:PATH = [Environment]::GetEnvironmentVariable('Path','Machine')+';'+[Environment]::GetEnvironmentVariable('Path','User')+';'+$env:PATH
    $awsLocal = Join-Path $env:LOCALAPPDATA 'Programs/Amazon/AWSCLIV2'
    if (Test-Path (Join-Path $awsLocal 'aws.exe')) { $env:PATH = $awsLocal+';'+$env:PATH }
}
terraform version
if ($LASTEXITCODE -ne 0) { throw 'Terraform no responde correctamente.' }
aws --version
if ($LASTEXITCODE -ne 0) { throw 'AWS CLI no responde correctamente.' }
Write-Host 'Instalacion verificada. No se crearon recursos AWS.'
