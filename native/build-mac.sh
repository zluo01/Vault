#!/usr/bin/env bash
#
# Builds the self-contained cover-conversion library for bundling into the
# Vault jar (macOS, native build for the host architecture).
#
# Same construction as the Linux build: libwebp, dav1d and libavif compiled
# as static libraries and linked with the vendored stb code into a single
# dylib depending only on macOS system libraries.
#
# Host requirements: Xcode command line tools (cc, make, git) plus cmake and
# python3 (brew install cmake python). pkg-config and meson/ninja are
# bootstrapped into the private tools prefix.
#
# Output: native/out/resources/libimage — the jar is built for ONE platform:
# run this script on the deployment target right before mvn package.

set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
. "$NATIVE_DIR/image/build.sh"

require_tools "xcode-select --install; brew install cmake python3" \
    cc make cmake curl python3

init_dirs
setup_env

build_pkgconf
bootstrap_meson ninja
if [ "$(uname -m)" = x86_64 ]; then
    build_nasm
fi

# ------------------------------------------------------------------ bundle

build_libimage mac
