#!/usr/bin/env bash
set -euo pipefail

# Reproducible setup for a legitimate free Linux NVIDIA GPU workspace.
# Tested target class: A100 40/80GB or L40S 48GB. Hunyuan Paint's official
# minimum recommendation is 21GB VRAM for 6 views / 512 resolution.

PIN="82920d643c0dc2f7bfd7255f45f62d386edfe60c"
ROOT="${1:-$PWD/Hunyuan3D-2.1}"
PYTHON_BIN="${PYTHON_BIN:-python3}"

if ! command -v nvidia-smi >/dev/null 2>&1; then
  echo "ERROR: NVIDIA GPU runtime is required." >&2
  exit 2
fi
nvidia-smi

if [[ ! -d "$ROOT/.git" ]]; then
  git clone https://github.com/Tencent-Hunyuan/Hunyuan3D-2.1.git "$ROOT"
fi
git -C "$ROOT" fetch --depth=1 origin "$PIN"
git -C "$ROOT" checkout --detach "$PIN"
[[ "$(git -C "$ROOT" rev-parse HEAD)" == "$PIN" ]]

# Install only system pieces needed by the official Paint renderer when apt is available.
APT=""
if command -v apt-get >/dev/null 2>&1; then
  if [[ "$(id -u)" == "0" ]]; then APT="apt-get";
  elif command -v sudo >/dev/null 2>&1; then APT="sudo apt-get"; fi
fi
if [[ -n "$APT" ]]; then
  $APT update -qq
  DEBIAN_FRONTEND=noninteractive $APT install -y --no-install-recommends \
    build-essential git git-lfs wget cmake pkg-config python3-dev \
    libegl1 libegl1-mesa-dev libgl1 libgl1-mesa-dev libgles2-mesa-dev \
    libglib2.0-0 libxrender1 libxi6 libxext6 libsm6
fi

# Keep the environment compatible with the official CUDA 12.4 recipe where possible.
$PYTHON_BIN -m pip install --upgrade pip wheel setuptools
$PYTHON_BIN -m pip install \
  torch==2.5.1 torchvision==0.20.1 torchaudio==2.5.1 \
  --index-url https://download.pytorch.org/whl/cu124
$PYTHON_BIN -m pip install -r "$ROOT/requirements.txt"

# Official custom rasterizer + inpainting extension.
(
  cd "$ROOT/hy3dpaint/custom_rasterizer"
  export TORCH_CUDA_ARCH_LIST="${TORCH_CUDA_ARCH_LIST:-7.5;8.0;8.6;8.9;9.0}"
  export CUDA_NVCC_FLAGS="${CUDA_NVCC_FLAGS:--allow-unsupported-compiler}"
  $PYTHON_BIN -m pip install -e .
)
(
  cd "$ROOT/hy3dpaint/DifferentiableRenderer"
  bash compile_mesh_painter.sh
)

mkdir -p "$ROOT/hy3dpaint/ckpt"
REALESRGAN="$ROOT/hy3dpaint/ckpt/RealESRGAN_x4plus.pth"
if [[ ! -s "$REALESRGAN" ]]; then
  wget -q --show-progress \
    https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth \
    -O "$REALESRGAN"
fi

# Upstream Dockerfile applies these path fixes; make the free-workspace setup equivalent
# while staying pinned to the exact upstream source revision.
sed -i 's#self\.multiview_cfg_path = "cfgs/hunyuan-paint-pbr.yaml"#self.multiview_cfg_path = "hy3dpaint/cfgs/hunyuan-paint-pbr.yaml"#' \
  "$ROOT/hy3dpaint/textureGenPipeline.py"
sed -i 's#custom_pipeline = config\.custom_pipeline#custom_pipeline = os.path.join(os.path.dirname(__file__),"..","hunyuanpaintpbr")#' \
  "$ROOT/hy3dpaint/utils/multiview_utils.py"

export PYOPENGL_PLATFORM=egl
export CUDA_HOME="${CUDA_HOME:-/usr/local/cuda}"

$PYTHON_BIN - <<'PY'
import torch
if not torch.cuda.is_available():
    raise SystemExit("CUDA unavailable after setup")
p=torch.cuda.get_device_properties(0)
gib=p.total_memory/(1024**3)
print(f"GPU={p.name}")
print(f"VRAM_GIB={gib:.2f}")
if gib < 21:
    raise SystemExit("Hunyuan3D-Paint 2.1 official 6-view/512 memory floor is 21 GiB")
print("HUNYUAN21_PAINT_ENV=READY")
PY

echo "PINNED_HUNYUAN_COMMIT=$PIN"
echo "HUNYUAN_ROOT=$ROOT"
echo "PAID_FALLBACK=false"
