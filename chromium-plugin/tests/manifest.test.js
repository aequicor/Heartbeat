// @ts-check
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const manifest = JSON.parse(readFileSync(new URL('../manifest.json', import.meta.url), 'utf8'));

/** The id Heartbeat allows in its native host manifest (`ExtensionIdentity.kt`). */
const EXPECTED_ID = 'dngnkjkjppehmiaebojfebjcfkcpdjki';

test('the public key pins the extension id Heartbeat allows', () => {
  const digest = createHash('sha256').update(Buffer.from(manifest.key, 'base64')).digest('hex').slice(0, 32);
  const id = [...digest].map((digit) => String.fromCharCode(97 + parseInt(digit, 16))).join('');

  assert.equal(id, EXPECTED_ID);
});

test('the manifest is a module service worker without page-facing entry points', () => {
  assert.equal(manifest.manifest_version, 3);
  assert.equal(manifest.background.type, 'module');
  for (const forbidden of ['content_scripts', 'externally_connectable', 'web_accessible_resources',
    'host_permissions', 'update_url']) {
    assert.equal(manifest[forbidden], undefined, forbidden);
  }
});

test('extension pages may only compile their own WebAssembly on top of the MV3 default policy', () => {
  // The Compose side panel is Kotlin/Wasm; Chrome supports WasmGC from 119.
  assert.deepEqual(manifest.content_security_policy, {
    extension_pages: "script-src 'self' 'wasm-unsafe-eval'; object-src 'self'",
  });
  assert.ok(Number(manifest.minimum_chrome_version) >= 119);
});

test('permissions are exactly the reviewed set', () => {
  assert.deepEqual([...manifest.permissions].sort(), [
    'activeTab',
    'alarms',
    'contextMenus',
    'debugger',
    'nativeMessaging',
    'scripting',
    'sidePanel',
    'storage',
    'tabGroups',
    'tabs',
  ]);
  assert.deepEqual(manifest.optional_host_permissions, ['http://*/*', 'https://*/*']);
});

test('the side panel starts only the bundled Compose application', () => {
  assert.equal(manifest.side_panel.default_path, 'src/sidepanel/panel.html');
  const html = readFileSync(new URL('../src/sidepanel/panel.html', import.meta.url), 'utf8');
  assert.ok(html.includes('src="panel-boot.js"'));
  assert.ok(html.includes('src="../../app/panel.js"'));
  assert.ok(!html.includes('sidepanel.js'));
});
