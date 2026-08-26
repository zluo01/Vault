# Image bundle: stb decode/scale/jpeg-encode (vendored headers) + static
# libwebp (webp decode) + static libavif/dav1d (avif decode), linked with
# cover_convert.c into the single /libimage resource.
#
# Not runnable on its own — sourced by the build-<os>.sh entry scripts after
# common.sh, then invoked as: build_libimage <linux|mac|windows>
#
# Reads the platform knobs from common.sh (CROSS_PREFIX, LIBWEBP_EXTRA_CONF,
# MESON_CROSS_ARGS, CMAKE_CROSS_ARGS) plus $ALLOWED from the entry script for
# the linux ldd check.

IMAGE_DIR="$NATIVE_DIR/image"

# Static libwebp, decode-only
build_libwebp() {
    if ! done_stamp libwebp; then
        fetch "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-$LIBWEBP_VERSION.tar.gz" "$LIBWEBP_SHA256"
        log "Building libwebp"
        ( cd "$SRC/libwebp-$LIBWEBP_VERSION" \
          && ./configure --prefix="$PREFIX" --enable-static --disable-shared --with-pic \
                 ${LIBWEBP_EXTRA_CONF:-} \
                 --disable-libwebpdemux --disable-libwebpmux --enable-libwebpdecoder \
                 --disable-png --disable-jpeg --disable-tiff --disable-gif --disable-wic \
                 --disable-sdl --disable-gl >/dev/null \
          && make -j"$JOBS" >/dev/null && make install >/dev/null )
        mark_done libwebp
    fi
}

# dav1d: the AV1 decoder under libavif. Decode-only by design; x86 SIMD is
# assembled by nasm (bootstrapped by the entry scripts when targeting x86).
build_dav1d() {
    if ! done_stamp dav1d; then
        fetch "https://downloads.videolan.org/pub/videolan/dav1d/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.xz" "$DAV1D_SHA256"
        log "Building dav1d"
        ( cd "$SRC/dav1d-$DAV1D_VERSION" \
          && meson setup build --prefix="$PREFIX" --libdir=lib --buildtype=release \
                 --default-library=static ${MESON_CROSS_ARGS:-} \
                 -Denable_tools=false -Denable_tests=false -Denable_examples=false >/dev/null \
          && ninja -C build >/dev/null && ninja -C build install >/dev/null )
        mark_done dav1d
    fi
}

# libavif: AVIF container parsing over dav1d, decode-only (no AV1 encoder).
build_libavif() {
    build_dav1d
    if ! done_stamp libavif; then
        fetch "https://github.com/AOMediaCodec/libavif/archive/refs/tags/v$LIBAVIF_VERSION.tar.gz" "$LIBAVIF_SHA256"
        log "Building libavif"
        ( cd "$SRC/libavif-$LIBAVIF_VERSION" \
          && cmake -S . -B build -G Ninja \
                 -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
                 -DCMAKE_INSTALL_LIBDIR=lib -DBUILD_SHARED_LIBS=OFF \
                 -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
                 -DAVIF_CODEC_DAV1D=SYSTEM -DAVIF_LIBYUV=OFF \
                 -DAVIF_BUILD_APPS=OFF -DAVIF_BUILD_EXAMPLES=OFF -DAVIF_BUILD_TESTS=OFF \
                 ${CMAKE_CROSS_ARGS:-} >/dev/null \
          && cmake --build build >/dev/null && cmake --install build >/dev/null )
        mark_done libavif
    fi
}

# dlopen the built bundle and run every decoder through the full pipeline:
# a synthesized PNG (stb), plus the committed webp and avif fixtures. Each
# must come back as JPEG. (Host-run platforms only.)
run_image_load_check() { # $1 = library path
    python3 - "$1" "$IMAGE_DIR/testdata" <<'EOF'
import ctypes, pathlib, struct, sys, zlib

def chunk(t, d):
    return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))

raw = b"".join(b"\x00" + bytes([255, 0, 0, 255] * 2) for _ in range(2))
png = (b"\x89PNG\r\n\x1a\n"
       + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0))
       + chunk(b"IDAT", zlib.compress(raw))
       + chunk(b"IEND", b""))

testdata = pathlib.Path(sys.argv[2])
samples = {
    "png (stb)": png,
    "webp": (testdata / "test.webp").read_bytes(),
    "avif": (testdata / "test.avif").read_bytes(),
}

lib = ctypes.CDLL(sys.argv[1])
lib.cover_to_jpeg.restype = ctypes.c_int
lib.cover_to_jpeg.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                              ctypes.c_int,
                              ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_size_t)]
lib.cover_free.argtypes = [ctypes.c_void_p]

for name, data in samples.items():
    out = ctypes.c_void_p()
    out_len = ctypes.c_size_t()
    ok = lib.cover_to_jpeg(data, len(data), 320, 480, 80,
                           ctypes.byref(out), ctypes.byref(out_len))
    assert ok == 1, f"cover_to_jpeg failed for {name}"
    assert out_len.value > 0, f"empty output for {name}"
    assert ctypes.string_at(out, 2) == b"\xff\xd8", f"output for {name} is not jpeg"
    lib.cover_free(out)
EOF
    log "libimage load check passed: png/webp/avif decode -> scale -> jpeg."
}

build_libimage() { # $1 = linux|mac|windows
    build_libwebp
    build_libavif

    # Link order: cover_convert references libavif, libavif references dav1d.
    local objs="$IMAGE_DIR/cover_convert.c
                $PREFIX/lib/libavif.a $PREFIX/lib/libdav1d.a
                $PREFIX/lib/libwebpdecoder.a"

    local dest="$OUT/libimage"
    log "Linking image bundle"
    case "$1" in
        linux)
            # --exclude-libs keeps the statically linked codec symbols private
            # so they cannot clash with other native libraries in the JVM.
            gcc $CFLAGS -shared -o "$dest" -Wl,--exclude-libs,ALL \
                -I"$IMAGE_DIR" -I"$PREFIX/include" $objs -lm -lpthread
            strip --strip-unneeded "$dest"

            log "Bundled library: $dest ($(du -h "$dest" | cut -f1))"
            local unexpected
            unexpected="$(ldd "$dest" | awk '{print $1}' | grep -Ev "$ALLOWED" || true)"
            if [ -n "$unexpected" ]; then
                echo "ERROR: unexpected dynamic dependencies in image bundle:"
                echo "$unexpected"
                exit 1
            fi
            run_image_load_check "$dest"
            ;;
        mac)
            cc $CFLAGS -dynamiclib -o "$dest" \
                -I"$IMAGE_DIR" -I"$PREFIX/include" $objs -lm
            strip -x "$dest"

            log "Bundled library: $dest ($(du -h "$dest" | cut -f1))"
            local unexpected
            unexpected="$(otool -L "$dest" | tail -n +2 | awk '{print $1}' \
                | grep -v "libimage" \
                | grep -Ev '^(/usr/lib/|/System/Library/)' || true)"
            if [ -n "$unexpected" ]; then
                echo "ERROR: unexpected dynamic dependencies in image bundle:"
                echo "$unexpected"
                exit 1
            fi
            run_image_load_check "$dest"
            ;;
        windows)
            # mingw appends .exe to extensionless output names; link with a
            # .dll name and rename to the fixed resource name afterwards.
            "${CROSS_PREFIX}gcc" $CFLAGS -shared -static -o "$WORK/libimage.dll" \
                -I"$IMAGE_DIR" -I"$PREFIX/include" $objs
            "${CROSS_PREFIX}strip" --strip-unneeded "$WORK/libimage.dll"
            mv "$WORK/libimage.dll" "$dest"

            log "Bundled library: $dest ($(du -h "$dest" | cut -f1))"
            local unexpected
            unexpected="$("${CROSS_PREFIX}objdump" -p "$dest" | grep "DLL Name" \
                | grep -Ei "libwinpthread|libgcc|libstdc|libssp" || true)"
            if [ -n "$unexpected" ]; then
                echo "ERROR: non-system DLL dependencies in image bundle:"
                echo "$unexpected"
                exit 1
            fi
            ;;
        *)
            echo "ERROR: unknown image bundle target: $1"
            exit 1
            ;;
    esac
}
