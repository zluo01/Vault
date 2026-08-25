#!/usr/bin/env bash
# Bundle the jpackage app image (target/dist/Vault) into a single-file AppImage
# at target/appimage/Vault-x86_64.AppImage.
#
# Prereq: run `mvn verify -Papp` first, and have appimagetool or linuxdeploy on PATH.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
image="$root/target/dist/Vault"
out="$root/target/appimage"

[ -d "$image" ] || { echo "Missing $image — run: mvn verify -Papp" >&2; exit 1; }

rm -rf "$out"
mkdir -p "$out/Vault.AppDir/usr"
cp -r "$image/bin" "$image/lib" "$out/Vault.AppDir/usr/"

ln -s usr/bin/Vault "$out/Vault.AppDir/AppRun"
cp "$image/lib/Vault.png" "$out/Vault.AppDir/vault.png"
cat > "$out/Vault.AppDir/vault.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Vault
Comment=Manager for movies, TV shows and comics
Exec=Vault
Icon=vault
Categories=AudioVideo;Video;
EOF

# Pack with appimagetool if present, else the packaging plugin extracted from
# linuxdeploy. Plain `linuxdeploy --output appimage` must NOT be used here: its
# dependency scanner cannot resolve the bundled JRE's libjvm.so (rpath-resolved)
# and aborts, while the tree jpackage built is already self-contained.
cd "$out"
if command -v appimagetool >/dev/null; then
  appimagetool Vault.AppDir
elif command -v linuxdeploy >/dev/null; then
  linuxdeploy --appimage-extract >/dev/null
  squashfs-root/usr/bin/linuxdeploy-plugin-appimage --appdir Vault.AppDir
  rm -rf squashfs-root
else
  echo "Need appimagetool or linuxdeploy on PATH" >&2
  exit 1
fi

ls -1 "$out"/*.AppImage
