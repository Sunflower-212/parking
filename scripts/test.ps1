param([switch]$IncludeBrowser,[switch]$IncludeBackground,[switch]$IncludeRecovery,[ValidateSet(1,16)][int]$CapacityBuckets=16)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    python scripts/init-env.py
    Get-Content .env | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
    }
    if($IncludeRecovery -and !$IncludeBackground){throw 'IncludeRecovery requires IncludeBackground'}
    $composeArgs=@('compose','-p','parking-verification','-f','compose.yml','-f','compose.test.yml')
    $infrastructure=@('mysql','redis','redis-cache')
    if($IncludeBackground){$composeArgs+=@('-f','compose.background.yml','-f','compose.background.test.yml','--profile','background');$infrastructure+='rabbitmq'}
    $env:CAPACITY_BUCKETS=[string]$CapacityBuckets
    $env:BACKGROUND_TASKS_ENABLED=if($IncludeBackground){'true'}else{'false'}
    $env:PARKING_BACKGROUND_TESTS=if($IncludeBackground){'true'}else{'false'}
    $env:PARKING_DOCKER_RECOVERY_TESTS=if($IncludeRecovery){'true'}else{'false'}
    docker @composeArgs up -d --wait @infrastructure
    if ($LASTEXITCODE -ne 0) { throw 'Verification infrastructure startup failed' }
    docker @composeArgs stop backend frontend
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop verification writers before migration' }
    foreach($name in @('004-capacity.sql','005-background-tasks.sql','006-capacity-buckets.sql')) {
        $source="SET @capacity_buckets=$CapacityBuckets;`n"+(Get-Content (Join-Path 'database/migrations' $name) -Raw)
        $source | docker @composeArgs exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" parking_springboot'
        if($LASTEXITCODE -ne 0){throw "Verification migration failed: $name"}
    }
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
        docker @composeArgs up -d --build --wait backend frontend
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
    if ($IncludeRecovery) {
        if (!$IncludeBrowser) {
            docker @composeArgs up -d --build --wait backend frontend
            if ($LASTEXITCODE -ne 0) { throw 'Recovery application startup failed' }
        }
        $env:PARKING_BACKGROUND_RECOVERY = 'true'
        python scripts/test-service-recovery.py
        if ($LASTEXITCODE -ne 0) { throw 'Service recovery verification failed' }
        python scripts/test-docker.py
        if ($LASTEXITCODE -ne 0) { throw 'HTTP smoke verification failed' }
    }
} finally { Pop-Location }
