#!/usr/bin/env bash
#
# Builds the self-contained cover-conversion library for bundling into the
# Vault jar (Linux, native build for the host architecture).
#
# libwebp (webp decode), dav1d and libavif (avif decode) are compiled as
# static libraries and linked with the vendored stb code into a single
# shared library. The only runtime dependencies of the result are baseline
# system libraries present on any Linux install (glibc). Everything is pure
# C — no C++ runtime.
#
# The result inherits the build host's glibc baseline — build on a machine no
# newer (glibc-wise) than the deployment target.
#
# Output: native/out/resources/libimage — the jar is built for ONE platform:
# run this script on (or for) the deployment target right before mvn package.

set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
. "$NATIVE_DIR/image/build.sh"

require_tools "sudo dnf install gcc make cmake ninja-build pkgconf curl python3
            or: sudo apt install build-essential cmake ninja-build pkg-config curl python3-venv" \
    gcc make cmake ninja pkg-config curl python3

# Baseline system libraries allowed as dynamic dependencies of the bundle.
ALLOWED='linux-vdso|ld-linux|libc\.so|libm\.so|libpthread\.so|libdl\.so|librt\.so|libgcc_s\.so'

init_dirs
setup_env

bootstrap_meson
if [ "$(uname -m)" = x86_64 ]; then
    build_nasm
fi

# ------------------------------------------------------------------ bundle

build_libimage linux
