param([switch]$IncludeBrowser)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    python scripts/init-env.py
    Get-Content .env | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
    }
    docker compose -p parking-verification -f compose.yml -f compose.test.yml up -d --wait mysql redis redis-cache
    if ($LASTEXITCODE -ne 0) { throw 'Verification infrastructure startup failed' }
    $env:PARKING_INTEGRATION_TESTS = 'true'
    $env:PARKING_COMPOSE_PROJECT = 'parking-verification'
    $env:TEST_DB_URL = 'jdbc:mysql://127.0.0.1:23307/parking_springboot?serverTimezone=Asia/Shanghai'
    $env:TEST_REDIS_PORT = '26380'
    $env:TEST_CACHE_PORT = '26381'
    Push-Location parking/parking
    try { .\mvnw.cmd -B verify; if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed' } }
    finally { Pop-Location }
    python scripts/test-migration.py
    if ($LASTEXITCODE -ne 0) { throw 'Migration verification failed' }
    Push-Location vue3/parking-vue
    try {
        npm ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
        npm test
        if ($LASTEXITCODE -ne 0) { throw 'HTTP error tests failed' }
        npm run test:components
        if ($LASTEXITCODE -ne 0) { throw 'Component tests failed' }
        npm run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
    } finally { Pop-Location }
    if ($IncludeBrowser) {
        docker compose -p parking-verification -f compose.yml -f compose.test.yml up -d --build --wait backend frontend
        if ($LASTEXITCODE -ne 0) { throw 'Verification application startup failed' }
        $env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $projectRoot '.work/browsers'
        Push-Location vue3/parking-vue
        try {
            npx playwright install chromium
            if ($LASTEXITCODE -ne 0) { throw 'Browser installation failed' }
            npm run test:e2e
            if ($LASTEXITCODE -ne 0) { throw 'Browser verification failed' }
        } finally { Pop-Location }
    }
} finally { Pop-Location }
