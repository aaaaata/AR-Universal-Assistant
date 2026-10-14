[CmdletBinding()]
param(
    [string]$PythonExe = "$env:USERPROFILE\anaconda3\envs\armahjong-py310\python.exe",
    [string]$ApiKey = "local-token",
    [int]$Port = 8000
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$requiredFiles = @(
    (Join-Path $repoRoot 'models\region\yolo26_hand_detect.pt'),
    (Join-Path $repoRoot 'models\tile\yolo26m_mahjong_detect_finetune.pt'),
    (Join-Path $repoRoot 'server\models\region.manifest.json'),
    (Join-Path $repoRoot 'server\models\tile.manifest.json')
)

if (-not (Test-Path -LiteralPath $PythonExe -PathType Leaf)) {
    throw "Python was not found: $PythonExe"
}
foreach ($requiredFile in $requiredFiles) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required server file was not found: $requiredFile"
    }
}

Set-Location -LiteralPath $repoRoot
$env:CLOUD_RUNTIME_ENV = 'local'
$env:CLOUD_DEV_FULL_IMAGE_HAND_REGION = '0'
$env:CLOUD_API_KEY = $ApiKey
$env:CLOUD_TILE_MANIFEST = 'server/models/tile.manifest.json'
$env:CLOUD_REGION_MANIFEST = 'server/models/region.manifest.json'
$env:CLOUD_MAX_IMAGE_PIXELS = '0'
$env:PYTHONPATH = '.'

& $PythonExe -m uvicorn server.app:app --host 0.0.0.0 --port $Port --workers 1
