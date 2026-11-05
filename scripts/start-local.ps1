param([switch]$Build)
$ErrorActionPreference='Stop'
$projectDir=Split-Path -Parent $PSScriptRoot
$stateDir=Join-Path $projectDir '.local'
$logDir=Join-Path $stateDir 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Set-Location -LiteralPath $projectDir

function Test-LocalPort([int]$Port) {
    $client=New-Object System.Net.Sockets.TcpClient
    try { return $client.ConnectAsync('127.0.0.1',$Port).Wait(400) -and $client.Connected }
    catch { return $false } finally { $client.Dispose() }
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    $dockerDesktop='D:/Docker/DockerDesktop/Docker Desktop.exe'
    if (!(Test-Path -LiteralPath $dockerDesktop)) { throw 'Please open Docker Desktop first.' }
    Start-Process -FilePath $dockerDesktop -WindowStyle Hidden
    for($attempt=0;$attempt -lt 30;$attempt++) {
        Start-Sleep -Seconds 2
        docker info *> $null
        if($LASTEXITCODE -eq 0) { break }
    }
    if($LASTEXITCODE -ne 0) { throw 'Docker is not ready. Wait for Docker Desktop and retry.' }
}
docker compose -f compose.local.yml up -d --wait mysql redis
if($LASTEXITCODE -ne 0) { throw 'MySQL or Redis failed to start.' }

$jar=Join-Path $projectDir 'vibe-music-backend/vibe-music-server/target/vibe-music-server-0.0.1-SNAPSHOT.jar'
if ($Build -or !(Test-Path -LiteralPath $jar)) {
    Push-Location 'vibe-music-backend'
    try { mvn -B -DskipTests package; if($LASTEXITCODE -ne 0) { throw 'Maven build failed.' } }
    finally { Pop-Location }
}
$processes=@()
$stateFile=Join-Path $stateDir 'pids.json'
if(Test-Path -LiteralPath $stateFile) { $processes=@(Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json) }

function Start-LocalService($Name,$Executable,$Arguments,$Directory,$Port) {
    if(Test-LocalPort $Port) { Write-Host "$Name already listening on $Port; no duplicate process started."; return }
    $process=Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $Directory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDir "$Name.log") -RedirectStandardError (Join-Path $logDir "$Name-error.log") -PassThru
    $script:processes += [pscustomobject]@{Name=$Name;Id=$process.Id}
    $script:processes | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $stateFile -Encoding UTF8
    Write-Host "$Name started, PID $($process.Id)"
}
$minioExe=Join-Path $stateDir 'tools/minio.exe'
if(!(Test-Path -LiteralPath $minioExe)) { throw 'Local MinIO binary missing; see Windows本地启动说明.md.' }
$env:MINIO_ROOT_USER='minioadmin'
$env:MINIO_ROOT_PASSWORD='vibe_minio_local_2026'
$env:MINIO_BROWSER_REDIRECT_URL='http://localhost:9001'
New-Item -ItemType Directory -Force -Path (Join-Path $stateDir 'minio-data') | Out-Null
Start-LocalService 'minio' $minioExe @('server','.local/minio-data','--address','127.0.0.1:9000','--console-address','127.0.0.1:9001') $projectDir 9000
$javaExe=(Get-Command java.exe).Source
$nodeExe=(Get-Command node.exe).Source
Start-LocalService 'backend' $javaExe @('-jar',('"'+$jar+'"'),'--spring.config.additional-location=file:./vibe-music-server/src/main/resources/') (Join-Path $projectDir 'vibe-music-backend') 8080
foreach($front in @(@('client','vibe-music-client',8090),@('admin','vibe-music-admin',8089))) {
    $frontDir=Join-Path $projectDir $front[1]
    if(!(Test-Path -LiteralPath (Join-Path $frontDir 'node_modules/vite/bin/vite.js'))) { throw "Install dependencies in $frontDir with pnpm install first." }
    $vite=Join-Path $frontDir 'node_modules/vite/bin/vite.js'
    Start-LocalService $front[0] $nodeExe @(('"'+$vite+'"'),'--host','127.0.0.1','--strictPort') $frontDir $front[2]
}
Write-Host 'Client http://127.0.0.1:8090 | Admin http://127.0.0.1:8089 | MinIO http://localhost:9001'
Write-Host "Logs: $logDir"
