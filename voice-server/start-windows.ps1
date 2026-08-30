$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Require-Command($name, $installHint) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        Write-Host "Missing required command: $name" -ForegroundColor Red
        Write-Host $installHint -ForegroundColor Yellow
        exit 1
    }
}

Require-Command "python" "Install Python 3.11 and enable Add Python to PATH."
Require-Command "ollama" "Install Ollama from the official Ollama installer, then run this script again."

if (-not (Test-Path ".venv\Scripts\python.exe")) {
    Write-Host "Creating Python environment..."
    python -m venv .venv
}

$Python = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"

Write-Host "Installing/updating Al-Shorti voice dependencies..."
& $Python -m pip install --upgrade pip
& $Python -m pip install -r requirements.txt

Write-Host "Ensuring Qwen3 8B is available..."
ollama pull qwen3:8b

$env:VOICE_DIR = Join-Path $PSScriptRoot "voices"
$env:OLLAMA_BASE_URL = "http://127.0.0.1:11434"
$env:OLLAMA_MODEL = "qwen3:8b"

Write-Host ""
Write-Host "Starting Al-Shorti real voice backend on port 8787..." -ForegroundColor Green
Write-Host "Keep this window open while using the app. The phone should be on the same Wi-Fi." -ForegroundColor Cyan
& $Python server.py
