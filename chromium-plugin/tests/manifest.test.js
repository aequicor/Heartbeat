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
    'content_security_policy', 'host_permissions', 'update_url']) {
    assert.equal(manifest[forbidden], undefined, forbidden);
  }
});

test('permissions are exactly the reviewed set', () => {
  assert.deepEqual([...manifest.permissions].sort(), [
    'activeTab',
    'alarms',
    'contextMenus',
    'nativeMessaging',
    'scripting',
    'sidePanel',
    'storage',
    'tabs',
  ]);
  assert.deepEqual(manifest.optional_host_permissions, ['http://*/*', 'https://*/*']);
});
