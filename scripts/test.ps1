$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    python scripts/init-env.py
    Get-Content .env | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
    }
    docker compose -f compose.yml -f compose.dev.yml up -d --wait mysql redis
    if ($LASTEXITCODE -ne 0) { throw 'Infrastructure startup failed' }
    $env:PARKING_INTEGRATION_TESTS = 'true'
    $env:TEST_DB_URL = 'jdbc:mysql://127.0.0.1:3307/parking_springboot?serverTimezone=Asia/Shanghai'
    $env:TEST_REDIS_PORT = '6380'
    Push-Location parking/parking
    try { .\mvnw.cmd -B verify; if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed' } }
    finally { Pop-Location }
    python scripts/test-migration.py
    if ($LASTEXITCODE -ne 0) { throw 'Migration verification failed' }
    Push-Location vue3/parking-vue
    try { npm ci; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }; npm test; if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed' }; npm run build; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' } }
    finally { Pop-Location }
}
finally { Pop-Location }
