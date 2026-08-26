#!/usr/bin/env bash
#
# Builds the self-contained cover-conversion library for bundling into the
# Vault jar (Windows x86-64, cross-compiled FROM LINUX with the mingw-w64
# toolchain).
#
# Same construction as the Linux build: all codec libraries statically
# linked, plus a fully static mingw runtime (-static bakes in winpthreads
# and libgcc) so the DLL depends only on Windows system DLLs.
#
# Host requirements (Fedora):
#   sudo dnf install mingw64-gcc
# (Debian/Ubuntu: sudo apt install gcc-mingw-w64-x86-64)
# plus the same base tools as the Linux build.
#
# Output: native/out/resources/libimage — the jar is built for ONE platform:
# this overwrites any previously built library, so package the Windows jar
# right after running this script.

set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
. "$NATIVE_DIR/image/build.sh"

CROSS_PREFIX=x86_64-w64-mingw32-
LIBWEBP_EXTRA_CONF="--host=x86_64-w64-mingw32"

require_tools "sudo dnf install mingw64-gcc gcc make cmake ninja-build pkgconf curl python3" \
    "${CROSS_PREFIX}gcc" gcc make cmake ninja pkg-config curl python3

init_dirs
setup_env

# Autotools prefers a host-prefixed x86_64-w64-mingw32-pkg-config when one
# exists (Fedora ships one that rewrites our prefix paths into the mingw
# sysroot). Pin the plain pkg-config; PKG_CONFIG_LIBDIR already isolates it.
export PKG_CONFIG=pkg-config

CROSS_FILE="$WORK/mingw64-cross.ini"
cat > "$CROSS_FILE" <<EOF
[binaries]
c = '${CROSS_PREFIX}gcc'
cpp = '${CROSS_PREFIX}g++'
ar = '${CROSS_PREFIX}ar'
strip = '${CROSS_PREFIX}strip'
windres = '${CROSS_PREFIX}windres'
dlltool = '${CROSS_PREFIX}dlltool'
pkg-config = 'pkg-config'

[host_machine]
system = 'windows'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
EOF
MESON_CROSS_ARGS="--cross-file $CROSS_FILE"

CMAKE_TOOLCHAIN="$WORK/mingw64-toolchain.cmake"
cat > "$CMAKE_TOOLCHAIN" <<EOF
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(CMAKE_C_COMPILER ${CROSS_PREFIX}gcc)
set(CMAKE_RC_COMPILER ${CROSS_PREFIX}windres)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
EOF
CMAKE_CROSS_ARGS="-DCMAKE_TOOLCHAIN_FILE=$CMAKE_TOOLCHAIN"

bootstrap_meson
build_nasm

# ------------------------------------------------------------------ bundle

build_libimage windows
log "NOTE: run the jar on a Windows machine to fully verify this library."
