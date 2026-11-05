$ErrorActionPreference='Stop'
$projectDir=Split-Path -Parent $PSScriptRoot
$stateFile=Join-Path $projectDir '.local/pids.json'
if(Test-Path -LiteralPath $stateFile) {
    $records=@(Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json)
    foreach($record in $records) {
        $managed=Get-CimInstance Win32_Process -Filter "ProcessId=$($record.Id)" -ErrorAction SilentlyContinue
        if($managed -and $managed.CommandLine -and $managed.CommandLine.Contains($projectDir)) {
            Stop-Process -Id $record.Id
            Write-Host "$($record.Name) stopped."
        }
    }
    '[]' | Set-Content -LiteralPath $stateFile -Encoding UTF8
}
Push-Location -LiteralPath $projectDir
try { docker compose -f compose.local.yml stop } finally { Pop-Location }
Write-Host 'Project stopped. Database, Redis and MinIO files are preserved. Docker Desktop remains open.'
