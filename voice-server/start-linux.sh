#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3.11 is required." >&2
  exit 1
fi
if ! command -v ollama >/dev/null 2>&1; then
  echo "Ollama is required. Install Ollama, then run this script again." >&2
  exit 1
fi

if [ ! -x .venv/bin/python ]; then
  echo "Creating Python environment..."
  python3 -m venv .venv
fi

PY="$PWD/.venv/bin/python"

echo "Installing/updating Al-Shorti voice dependencies..."
"$PY" -m pip install --upgrade pip
"$PY" -m pip install -r requirements.txt

echo "Ensuring Qwen3 8B is available..."
ollama pull qwen3:8b

export VOICE_DIR="$PWD/voices"
export OLLAMA_BASE_URL="http://127.0.0.1:11434"
export OLLAMA_MODEL="qwen3:8b"

echo
printf '%s\n' "Starting Al-Shorti real voice backend on port 8787..." \
  "Keep this terminal open while using the app. The phone should be on the same Wi-Fi."
exec "$PY" server.py
