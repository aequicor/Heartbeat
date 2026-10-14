# Isolated WebView surfaces

Load with `ui_reference(topic="service-as-ui/webview")`. Use this mode for custom layouts and interactions on Desktop. The native JSON layout remains the portable option.

## Node and access

A `web` node contains `html`, optional `bridge` (default false), optional `data` (exact binding), and `height` (`compact`, `regular`, `tall`, `content`; default regular). Only one is allowed in the whole spec, counting its body and all named modals.

Owner-backed library surfaces may use WebView in their body or a modal, including `card` and `composer` placements. Anonymous specs may include it only inside a modal and never with a bridge. Editor previews do not execute the owner bridge. Desktop and enabled web documents are required; otherwise the host shows an unavailable note.

A document has an isolated origin, in-memory browser context and no network. Keep CSS, JavaScript and artwork self-contained. Inline styles/scripts and `data:` images/fonts are available. No fetch, sockets, WebRTC, external assets/CDNs, frames, workers, form submissions, popups or navigation away. A local HTML form may handle its submit event with `preventDefault()` and call the owner bridge. Use inline SVG/CSS or embedded image data for illustration. Do not put credentials into HTML. Service IO belongs in authorized owner methods, not in the browser page.

## Bridge and lifecycle

- Initial data is `heartbeat.data`. Read later values from `window`'s `heartbeat:data` event, using `event.detail`.
- With `bridge:true`, `await heartbeat.call(method, args)` invokes a published owner method. `args` is an object. It returns the method value or rejects with an `Error` containing the owner's message.
- It is not a general tool API: there is no supported `heartbeat.open`, file API or arbitrary tool execution. Native actions can surround the WebView when host operations are needed.
- Call mutation methods from user actions, keep an in-flight guard, and display errors near the action. Retain the same operation ID for a retry if a write's outcome is uncertain and the owner supports deduplication.
- Owner-data changes refresh auto sources and deliver new data to the page. Merge new saved state without replacing focused inputs or clearing local drafts. Avoid snapshot polling after every call.
- Hiding/reopening the canonical app and moving it between start and modal retain the document and drafts within the same owner/context. Changing its HTML/spec/owner or leaving that context can replace it. Durable records and deadlines belong in owner storage.
- `window.alert`, `confirm` and `prompt` are blocked. Render status and errors inline. A button must not depend on a blocked confirmation returning true.

## Sizing and scroll ownership

`compact`, `regular` and `tall` are bounded embedded viewport presets. `content` measures the body or an element marked `data-heartbeat-root` and grows/shrinks within the host bounds.

The canonical full app uses its host presentation rather than that preset: start fills the available app region; its modal follows content height, capped by the screen, and shrinks again on a shorter tab. Keep normal document flow and one page scroll when content exceeds the cap. Avoid fixed page heights, `min-height:100vh`, artificial bottom space or nested scrolling cards.

The canonical mini-app Start surface and modal with `web` as their root are edge-to-edge within the host's rounded outline. The host overlays an expand button on Start and a close button in the modal at the top end, without another padded title bar. CSS variables `--hb-overlay-width` and `--hb-overlay-height` report the occupied area in CSS pixels (zero outside these presentations). Account for the area in the header or toolbar, without adding a blank full-width strip. Do not draw a second outer frame, shadow, expand or close button. Named local modals opened with `open.modal` retain the host's title, padding and close action; their WebView content should fit that framed dialog.

## Host theme tokens

The host supplies a system-sans body and inherited form fonts. Use semantic tokens so light/dark theme changes work without reloading.

| Role | CSS variables |
|---|---|
| Surfaces and text | `--hb-surface`, `--hb-surface-raised`, `--hb-text`, `--hb-text-muted`, `--hb-border` |
| Accent and controls | `--hb-accent`, `--hb-on-accent`, `--hb-accent-muted`, `--hb-control`, `--hb-input`, `--hb-focus` |
| Feedback | `--hb-danger`, `--hb-on-danger`, `--hb-success`, `--hb-on-success` |
| Typography | `--hb-font-family`, `--hb-font-size`, `--hb-line-height`, `--hb-body-size`, `--hb-body-line-height`, `--hb-title-size`, `--hb-caption-size` |
| Spacing and control size | `--hb-space-xs`, `--hb-space-s`, `--hb-space-m`, `--hb-space-l`, `--hb-space-xl`, `--hb-control-height` |
| Start expand / modal close overlay | `--hb-overlay-width`, `--hb-overlay-height` |

`heartbeat.theme` contains `colorScheme` (`light` or `dark`) and `variables`. Listen to `heartbeat:theme` if a canvas or other non-CSS view needs a repaint. The CSS variables update directly; do not infer the app theme only from `prefers-color-scheme`. Theme changes neither change data nor grant method access.

## Minimal working page

This HTML is the value of a `web.html` field, JSON-encoded as a string. Pair it with `bridge:true`, `data:"{{data.snapshot}}"`, `height:"content"`, and a read-only `snapshot` source. The illustrative owner publishes `save_note({text})` and `snapshot` returns `{note:string}`. Adapt those methods to the actual owner; this example does not create them.

```html
<!doctype html>
<html lang="en">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
* { box-sizing: border-box; }
body { margin: 0; color: var(--hb-text); background: var(--hb-surface); }
main { padding: var(--hb-space-l); }
header {
  display: flex; align-items: center;
  padding-inline-end: var(--hb-overlay-width, 0px);
  min-height: var(--hb-overlay-height, 0px);
}
h1 { font-size: var(--hb-title-size); margin: 0 0 var(--hb-space-m); }
label { display: block; margin-block-end: var(--hb-space-xs); }
textarea {
  width: 100%; padding: var(--hb-space-s); border-radius: 8px;
  border: 1px solid var(--hb-border); color: var(--hb-text);
  background: var(--hb-input); resize: vertical;
}
button {
  min-height: var(--hb-control-height); padding-inline: var(--hb-space-m);
  border: 0; border-radius: 8px; background: var(--hb-accent);
  color: var(--hb-on-accent); cursor: pointer;
}
button:disabled { opacity: .6; cursor: default; }
:focus-visible { outline: 2px solid var(--hb-focus); outline-offset: 2px; }
.actions { display: flex; align-items: center; flex-wrap: wrap; gap: var(--hb-space-s); margin-block-start: var(--hb-space-s); }
#status { color: var(--hb-text-muted); }
</style>
<main data-heartbeat-root>
  <header><h1>Session note</h1></header>
  <label for="note">Note</label>
  <textarea id="note" rows="3"></textarea>
  <div class="actions"><button id="save" type="button">Save note</button><span id="status" role="status"></span></div>
</main>
<script>
const input = document.getElementById('note');
const button = document.getElementById('save');
const status = document.getElementById('status');
let dirty = false, busy = false;
function receive(data) {
  if (!dirty && document.activeElement !== input) input.value = data?.note ?? '';
}
input.addEventListener('input', () => { dirty = true; });
window.addEventListener('heartbeat:data', event => receive(event.detail));
receive(heartbeat.data);
button.addEventListener('click', async () => {
  if (busy) return;
  busy = true; button.disabled = true; status.textContent = 'Saving…';
  const submitted = input.value;
  try {
    await heartbeat.call('save_note', {text: submitted});
    dirty = input.value !== submitted;
    status.textContent = dirty ? 'Saved. Your newer edit is not saved yet.' : 'Saved';
  } catch (error) {
    status.textContent = error.message || 'Could not save. Your note is still here.';
  } finally {
    busy = false; button.disabled = false;
  }
});
</script>
</html>
```

This pattern keeps the input DOM and draft intact during updates. For record creation, add owner-side deduplication rather than treating a disabled button as the only duplicate safeguard. Validate scroll, keyboard focus, close-overlay clearance, theme changes and actual save/reopen behavior in the live host; HTML rendering in a standalone browser does not verify the owner bridge.
