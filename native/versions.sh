# Pinned versions and tarball checksums for the bundled libimage build,
# shared by every build-<os>.sh script. Bump here, then rerun the script(s) —
# stamps are fingerprinted to this file, so any change triggers a clean
# rebuild.
#
# The "# renovate:" annotations let Renovate open PRs when upstream releases;
# a version bump then requires updating the matching *_SHA256 (the build
# fails with both sums printed — verify the new tarball before copying).

# renovate: datasource=github-tags depName=netwide-assembler/nasm versioning=loose extractVersion=^nasm-(?<version>.+)$
NASM_VERSION=3.02
NASM_SHA256=87336eba53b4acfe917424ab5d500d2b0054d9f5148d35c2273ccf2cfb712f0d

# renovate: datasource=github-tags depName=pkgconf/pkgconf extractVersion=^pkgconf-(?<version>.+)$
PKGCONF_VERSION=2.5.1
PKGCONF_SHA256=3a9080ac51d03615e7c1910a0a2a8df08424892b5f13b0628a204d3fcce0ea8b

# renovate: datasource=github-tags depName=webmproject/libwebp extractVersion=^v(?<version>.+)$
LIBWEBP_VERSION=1.6.0
LIBWEBP_SHA256=e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564

# renovate: datasource=gitlab-tags depName=videolan/dav1d registryUrl=https://code.videolan.org
DAV1D_VERSION=1.5.4
DAV1D_SHA256=686616b7c69eb88d44459391ab25cac13b6647a3b288835c5784e71c1514a5c5

# renovate: datasource=github-tags depName=AOMediaCodec/libavif extractVersion=^v(?<version>.+)$
LIBAVIF_VERSION=1.4.2
LIBAVIF_SHA256=2b645287340ba5a631d268b551dc2d72bd73ac33335962dd36dcdb6d8366921d

# renovate: datasource=pypi depName=meson
MESON_VERSION=1.12.0
