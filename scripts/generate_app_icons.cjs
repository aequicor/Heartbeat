#!/usr/bin/env node
// Export platform icons from the brand PNG and optical ECG SVGs. Requires Node.js and sharp 0.35.4.
// Run from any directory: node scripts/generate_app_icons.cjs
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('sharp');

const root = path.resolve(__dirname, '..');
const brand = 'assets/branding';
const desktop = 'platform-main/desktop';
const android = 'platform-main/android/src/main/res';
const ios = 'platform-main/ios/iosApp/Assets.xcassets/AppIcon.appiconset';

async function save(relative, data) {
  const destination = path.join(root, relative);
  await fs.mkdir(path.dirname(destination), { recursive: true });
  await fs.writeFile(destination, data);
}

async function png(input, size) {
  return sharp(input).resize(size, size).png().toBuffer();
}

// Match the 824px tile / 100px inset of a standard 1024px macOS icon.
// Normalize the raster and optical SVG before resizing so every ICNS entry
// has the same square silhouette and alignment, independent of source padding.
async function macosCanvas(input) {
  const { data, info } = await sharp(input).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  let left = info.width;
  let top = info.height;
  let right = 0;
  let bottom = 0;
  for (let y = 0; y < info.height; y++) {
    for (let x = 0; x < info.width; x++) {
      if (data[(y * info.width + x) * info.channels + 3] >= 128) {
        left = Math.min(left, x);
        top = Math.min(top, y);
        right = Math.max(right, x + 1);
        bottom = Math.max(bottom, y + 1);
      }
    }
  }
  if (left >= right || top >= bottom) throw new Error('macOS artwork has no visible tile.');
  const tile = await sharp(input)
    .extract({ left, top, width: right - left, height: bottom - top })
    .resize(824, 824, { fit: 'fill' }).png().toBuffer();
  return sharp({ create: { width: 1024, height: 1024, channels: 4, background: '#00000000' } })
    .composite([{ input: tile, left: 100, top: 100 }]).png().toBuffer();
}

// PNG-compressed ICO entries are supported by Windows Vista and later.
function ico(entries) {
  const header = Buffer.alloc(6 + entries.length * 16);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(entries.length, 4);
  let offset = header.length;
  entries.forEach(({ size, data }, index) => {
    const start = 6 + index * 16;
    header[start] = size === 256 ? 0 : size;
    header[start + 1] = header[start];
    header.writeUInt16LE(1, start + 4);
    header.writeUInt16LE(32, start + 6);
    header.writeUInt32LE(data.length, start + 8);
    header.writeUInt32LE(offset, start + 12);
    offset += data.length;
  });
  return Buffer.concat([header, ...entries.map(entry => entry.data)]);
}

function icns(entries) {
  const chunks = entries.map(({ type, data }) => {
    const header = Buffer.alloc(8);
    header.write(type, 0, 4, 'ascii');
    header.writeUInt32BE(data.length + 8, 4);
    return Buffer.concat([header, data]);
  });
  const header = Buffer.alloc(8);
  header.write('icns', 0, 4, 'ascii');
  header.writeUInt32BE(8 + chunks.reduce((sum, chunk) => sum + chunk.length, 0), 4);
  return Buffer.concat([header, ...chunks]);
}

async function main() {
  const source = await fs.readFile(path.join(root, brand, 'heartbeat-logo.png'));
  const desktopSource = await fs.readFile(path.join(root, brand, 'heartbeat-desktop-logo.png'));
  const desktopSmallSource = await fs.readFile(path.join(root, brand, 'heartbeat-app-icon.svg'));
  const desktopIcon = size => png(size <= 32 ? desktopSmallSource : desktopSource, size);
  const macosSource = await fs.readFile(path.join(root, brand, 'heartbeat-macos-logo.png'));
  const macosSmallSource = await fs.readFile(path.join(root, brand, 'heartbeat-macos-small.svg'));
  const macosMaster = await macosCanvas(macosSource);
  const macosSmallMaster = await macosCanvas(macosSmallSource);
  const macosIcon = size => png(size <= 32 ? macosSmallMaster : macosMaster, size);
  await save(`${brand}/heartbeat-macos-icon.png`, macosMaster);
  const monochrome = await fs.readFile(path.join(root, brand, 'heartbeat-monochrome.svg'), 'utf8');
  const mark = monochrome.match(/<path id="mark"[^>]*\/>/)[0];
  const geometry = mark.match(/ d="([^"]+)"/)[1];
  const stroke = mark.match(/ stroke-width="([^"]+)"/)[1];
  const canvas = (first, last) => Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024"><defs><linearGradient id="bg" x2="1" y2="1"><stop stop-color="${first}"/><stop offset="1" stop-color="${last}"/></linearGradient></defs><rect width="1024" height="1024" fill="url(#bg)"/></svg>`);
  const desktopPng = await png(source, 1024);
  const normal = await sharp(canvas('#363344', '#18203A')).composite([{ input: desktopPng }]).removeAlpha().png().toBuffer();
  const dark = await sharp(canvas('#18203A', '#10121F')).composite([{ input: desktopPng }]).removeAlpha().png().toBuffer();
  const tinted = await sharp(dark).grayscale().removeAlpha().png().toBuffer();

  // Keep the glass artwork in the Dock; 16–32px entries use a clearer optical SVG.
  await save(`${desktop}/icons/heartbeat.png`, await desktopIcon(1024));
  await save(`${desktop}/src/main/resources/icons/heartbeat.png`, await desktopIcon(256));
  const icoEntries = [];
  for (const size of [16, 20, 24, 32, 40, 48, 64, 128, 256]) {
    const data = await desktopIcon(size);
    icoEntries.push({ size, data });
    await save(`${desktop}/src/main/resources/icons/heartbeat-${size}.png`, data);
    await save(`${desktop}/src/main/resources/icons/macos/heartbeat-${size}.png`, await macosIcon(size));
  }
  await save(`${desktop}/icons/heartbeat.ico`, ico(icoEntries));
  const icnsEntries = [];
  for (const [type, size] of [['icp4', 16], ['icp5', 32], ['icp6', 64], ['ic07', 128],
    ['ic08', 256], ['ic09', 512], ['ic10', 1024], ['ic11', 32], ['ic12', 64], ['ic13', 256], ['ic14', 512]]) {
    icnsEntries.push({ type, data: await macosIcon(size) });
  }
  await save(`${desktop}/icons/heartbeat.icns`, icns(icnsEntries));

  for (const [density, size] of [['mdpi', 48], ['hdpi', 72], ['xhdpi', 96], ['xxhdpi', 144], ['xxxhdpi', 192]]) {
    await save(`${android}/mipmap-${density}/ic_launcher.png`, await png(source, size));
    const circle = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}"><circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="#18203A"/></svg>`);
    await save(`${android}/mipmap-${density}/ic_launcher_round.png`, await sharp(circle).composite([{ input: await png(source, size) }]).png().toBuffer());
  }
  // Fit all nontransparent pixels into the adaptive icon's 66dp safe circle on a 108dp canvas.
  const { data, info } = await sharp(desktopPng).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  let radius = 1;
  for (let y = 0; y < info.height; y++) {
    for (let x = 0; x < info.width; x++) {
      if (data[(y * info.width + x) * info.channels + 3] > 8) {
        radius = Math.max(radius, Math.hypot(x + 0.5 - info.width / 2, y + 0.5 - info.height / 2));
      }
    }
  }
  const foregroundSize = 432;
  const contentSize = Math.floor(128 * info.width / radius);
  const foreground = await sharp({ create: { width: foregroundSize, height: foregroundSize, channels: 4, background: '#00000000' } })
    .composite([{ input: await png(desktopPng, contentSize), gravity: 'centre' }]).png().toBuffer();
  await save(`${android}/drawable-nodpi/heartbeat_foreground.png`, foreground);
  const vector = color => `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="1024" android:viewportHeight="1024">
    <!-- Keep the entire mark inside the adaptive icon's 66dp safe circle. -->
    <group android:scaleX="0.75" android:scaleY="0.75" android:translateX="128" android:translateY="128">
        <path android:pathData="${geometry}" android:fillColor="#00000000"
            android:strokeColor="${color}" android:strokeWidth="${stroke}"
            android:strokeLineCap="round" android:strokeLineJoin="round" />
    </group>
</vector>
`;
  await save(`${android}/drawable-v24/ic_launcher_foreground.xml`, `<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
    android:src="@drawable/heartbeat_foreground" android:gravity="fill" android:filter="true" />
`);
  await save(`${android}/drawable/ic_launcher_monochrome.xml`, vector('#FFFFFF'));
  await save(`${android}/drawable/ic_launcher_background.xml`, `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#18203A" android:pathData="M0,0H108V108H0Z" />
</vector>
`);
  for (const filename of ['ic_launcher.xml', 'ic_launcher_round.xml']) {
    await save(`${android}/mipmap-anydpi-v33/${filename}`, `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
`);
  }

  // iOS applies its own corner mask; every 1024px source must be full-bleed and opaque.
  await save(`${ios}/app-icon-1024.png`, normal);
  await save(`${ios}/app-icon-dark-1024.png`, dark);
  await save(`${ios}/app-icon-tinted-1024.png`, tinted);
  console.log('Exported Heartbeat icons for Android, iOS, Windows and macOS.');
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
