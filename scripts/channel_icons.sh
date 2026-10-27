#!/usr/bin/env bash
# Regenerates the badged desktop icons of the dev and beta channels from the release icons (macOS only: swift,
# iconutil). Outputs: platform-main/desktop/icons/<channel>/heartbeat.{icns,ico,png} for packaging and
# platform-main/desktop/src/main/resources/icons/<channel>/ for window and Dock icons.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
desktop="$root/platform-main/desktop"
badge() { swift "$root/scripts/channel_icon.swift" "$1" "$2" "$3" "$4"; }
for channel in beta dev; do
  packaging="$desktop/icons/$channel"
  resources="$desktop/src/main/resources/icons/$channel"
  work="$(mktemp -d)"
  mkdir -p "$packaging" "$resources/macos" "$work/heartbeat.iconset"
  for size in 16 20 24 32 40 48 64 128 256; do
    badge "$channel" "$desktop/src/main/resources/icons/heartbeat-$size.png" "$size" "$resources/heartbeat-$size.png"
    badge "$channel" "$desktop/src/main/resources/icons/macos/heartbeat-$size.png" "$size" \
      "$resources/macos/heartbeat-$size.png"
  done
  badge "$channel" "$desktop/icons/heartbeat.png" 1024 "$packaging/heartbeat.png"
  for size in 16 32 128 256 512; do
    badge "$channel" "$desktop/icons/heartbeat.png" "$size" "$work/heartbeat.iconset/icon_${size}x${size}.png"
    badge "$channel" "$desktop/icons/heartbeat.png" $((size * 2)) "$work/heartbeat.iconset/icon_${size}x${size}@2x.png"
  done
  iconutil -c icns "$work/heartbeat.iconset" -o "$packaging/heartbeat.icns"
  for size in 16 32 48 64 128 256; do
    badge "$channel" "$desktop/icons/heartbeat.png" "$size" "$work/ico-$size.png"
  done
  python3 - "$work" "$packaging/heartbeat.ico" <<'PY'
import struct, sys
work, out = sys.argv[1], sys.argv[2]
sizes = [16, 32, 48, 64, 128, 256]
images = [open(f"{work}/ico-{s}.png", "rb").read() for s in sizes]
header = struct.pack("<HHH", 0, 1, len(sizes))
offset = 6 + 16 * len(sizes)
entries = b""
for size, data in zip(sizes, images):
    entries += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(data), offset)
    offset += len(data)
open(out, "wb").write(header + entries + b"".join(images))
PY
  rm -rf "$work"
done
