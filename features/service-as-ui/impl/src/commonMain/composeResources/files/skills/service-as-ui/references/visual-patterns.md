# A self-contained app layout

Load with `ui_reference(topic="service-as-ui/visual-patterns")` when a custom WebView form or multi-view layout is justified. Read `service-as-ui/webview` for the host contract first. This is an adaptable working pattern, not a required aesthetic or a reason to add navigation to a single-task form. Retain the user's requested style, actual workflow and existing records.

This is an interaction/layout recipe: related fields that reflow, visible field labels/helpers/errors, selected task navigation and distinct primary/secondary actions. Its restrained note-editor treatment is not a sufficient visual direction for every product. Choose your actual product's direction with `mini-app-design`, then adapt composition, hierarchy, accents and artwork. The pattern uses host tokens, one natural document scroll, overlay clearance and stable input nodes. It requires no external styles, fonts, icons, JavaScript libraries or network access. Native controls remain useful for simple host forms and portable UI.

## Owner contract

Use this HTML as the JSON-encoded `html` of the canonical `card`'s root `web`, with `bridge:true`, `height:"content"`, `data:"{{data.snapshot}}"` and an auto-loading `snapshot` source. This example assumes these published methods already exist:

- `snapshot()` returns `{title:string, topic:"general"|"work"|"personal", note:string}`. Empty strings represent an empty saved note.
- `save_note({title,topic,note})` validates the fields, durably replaces the single note, and returns the saved snapshot. Replacement is idempotent for the same input. Record-creation workflows need owner-side operation IDs/deduplication instead.

Adapt the methods, limits, labels, language and visual character to the actual owner. The example creates no owner and fabricates no saved records. Error and pending states remain in the page; native JavaScript dialogs are not required.

```html
<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body { margin: 0; background: var(--hb-surface); color: var(--hb-text);
  font-family: var(--hb-font-family); font-size: var(--hb-font-size); line-height: var(--hb-line-height); }
*, *::before, *::after { box-sizing: border-box; }
main { max-width: 64rem; margin-inline: auto; padding: var(--hb-space-l); }
header { padding-inline-end: var(--hb-overlay-width, 0px); min-height: var(--hb-overlay-height, 0px); }
h1, h2, p { margin: 0; overflow-wrap: anywhere; }
h1, h2 { font-size: var(--hb-title-size); line-height: 1.25; font-weight: 650; }
.description, .help, .saved-text { font-size: var(--hb-body-size); line-height: var(--hb-body-line-height); }
.description, .help, #status { color: var(--hb-text-muted); }
.description { margin-block-start: var(--hb-space-xs); }
nav { display: flex; flex-wrap: wrap; gap: var(--hb-space-xs); margin-block: var(--hb-space-l);
  padding-block-end: var(--hb-space-s); border-bottom: 1px solid var(--hb-border); }
button, input, select, textarea { font: inherit; color: inherit; }
button { min-height: var(--hb-control-height); padding: var(--hb-space-xs) var(--hb-space-m);
  border: 1px solid var(--hb-border); border-radius: var(--hb-control-radius);
  background: var(--hb-control); cursor: pointer; white-space: normal; }
button:not(:disabled):hover { background: var(--hb-control-hover); }
button:not(:disabled):active { background: var(--hb-control-pressed); }
nav button { border-color: transparent; background: transparent; }
nav button[aria-current="page"] { background: var(--hb-accent-muted); border-color: var(--hb-accent); font-weight: 650; }
.primary, .primary:not(:disabled):hover, .primary:not(:disabled):active {
  background: var(--hb-accent); color: var(--hb-on-accent); border-color: transparent; }
.primary:not(:disabled):hover { text-decoration: underline; text-underline-offset: .2em; }
button:disabled { background: var(--hb-control); color: var(--hb-text-muted); cursor: default; }
:focus-visible { outline: var(--hb-focus-width) solid var(--hb-focus); outline-offset: var(--hb-focus-offset); }
.view:not([hidden]), form, .field { display: grid; gap: var(--hb-space-m); }
.field { gap: var(--hb-space-xs); min-width: 0; align-content: start; }
.fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 16em), 1fr)); gap: var(--hb-space-m); }
label { font-weight: 600; }
input, select, textarea { width: 100%; min-width: 0; min-height: var(--hb-control-height);
  padding: var(--hb-space-s) var(--hb-space-m); border: 1px solid var(--hb-border);
  border-radius: var(--hb-field-radius); background: var(--hb-input); }
textarea { resize: vertical; }
[aria-invalid="true"] { border-color: var(--hb-danger); }
.error { color: var(--hb-text); border-inline-start: 3px solid var(--hb-danger); padding-inline-start: var(--hb-space-s); }
.actions { display: flex; align-items: center; flex-wrap: wrap; gap: var(--hb-space-s); }
.saved-text { white-space: pre-wrap; }
.saved-topic { color: var(--hb-text-muted); font-size: var(--hb-caption-size); }
</style>
<main data-heartbeat-root>
  <header><h1>Session note</h1><p class="description">Capture a thought and return to it later.</p></header>
  <nav aria-label="Note views">
    <button type="button" data-view="write" aria-controls="write" aria-current="page">Write</button>
    <button type="button" data-view="saved" aria-controls="saved">Saved note</button>
  </nav>
  <section id="write" class="view" aria-label="Write a note">
    <form id="editor" novalidate>
      <div class="fields">
        <div class="field"><label for="title">Title</label>
          <input id="title" name="title" maxlength="120" autocomplete="off" aria-describedby="title-help" disabled>
          <p id="title-help" class="help">Optional. A short name makes this note easier to recognize.</p>
        </div>
        <div class="field"><label for="topic">Topic</label>
          <select id="topic" name="topic" disabled><option value="general">General</option><option value="work">Work</option><option value="personal">Personal</option></select>
        </div>
      </div>
      <div class="field"><label for="note">Note</label>
        <textarea id="note" name="note" rows="5" maxlength="4000" required aria-describedby="note-help note-error" disabled></textarea>
        <p id="note-help" class="help">Write up to 4,000 characters. Your draft stays here when you switch views.</p>
        <p id="note-error" class="error" role="alert" hidden></p>
      </div>
      <div class="actions"><button id="save" type="submit" class="primary" disabled>Save note</button>
        <button type="button" id="review">Read saved note</button></div>
      <p id="status" role="status">Loading saved note…</p>
      <p id="save-error" class="error" role="alert" hidden></p>
    </form>
  </section>
  <section id="saved" class="view" aria-label="Saved note" hidden>
    <h2 id="saved-title">Loading saved note…</h2><p id="saved-topic" class="saved-topic"></p>
    <p id="saved-body" class="saved-text"></p>
    <div class="actions"><button type="button" id="edit">Return to draft</button></div>
  </section>
</main>
<script>
const form = document.getElementById('editor');
const fields = ['title', 'topic', 'note'].map(id => document.getElementById(id));
const [title, topic, note] = fields;
const save = document.getElementById('save'), status = document.getElementById('status');
const noteError = document.getElementById('note-error'), saveError = document.getElementById('save-error');
const nav = Array.from(document.querySelectorAll('nav button'));
let saved = null, dirty = false, busy = false;
function draft() { return {title: title.value, topic: topic.value, note: note.value}; }
function fill() { for (const field of fields) field.value = saved[field.id]; }
function showView(id) {
  for (const button of nav) {
    const active = button.dataset.view === id;
    if (active) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
    document.getElementById(button.dataset.view).hidden = !active;
  }
}
for (const button of nav) button.addEventListener('click', () => showView(button.dataset.view));
document.getElementById('review').addEventListener('click', () => { showView('saved'); nav[1].focus(); });
document.getElementById('edit').addEventListener('click', () => { showView('write'); nav[0].focus(); });
function receive(value) {
  if (!value || typeof value.title !== 'string' || typeof value.note !== 'string' ||
      !['general', 'work', 'personal'].includes(value.topic)) return false;
  const first = saved === null;
  saved = value;
  if (first || (!dirty && !busy && !fields.includes(document.activeElement))) fill();
  for (const field of fields) field.disabled = false;
  save.disabled = busy;
  document.getElementById('saved-title').textContent = saved.note ? saved.title || 'Untitled note' : 'No saved note yet';
  document.getElementById('saved-topic').textContent = saved.note ? topic.options[['general', 'work', 'personal'].indexOf(saved.topic)].text : '';
  document.getElementById('saved-body').textContent = saved.note || 'Write your first note, then save it here.';
  if (first) status.textContent = 'Ready';
  return true;
}
for (const field of fields) {
  field.addEventListener('input', () => {
    dirty = true; status.textContent = busy ? 'Saving… Your latest edits will remain as a draft.' : 'Unsaved changes';
    if (field === note) { note.removeAttribute('aria-invalid'); noteError.hidden = true; }
  });
  field.addEventListener('blur', () => { if (saved && !dirty && !busy) fill(); });
}
form.addEventListener('submit', async event => {
  event.preventDefault();
  if (!saved || busy) return;
  if (!note.value.trim()) {
    noteError.textContent = 'Write a note before saving.'; noteError.hidden = false;
    note.setAttribute('aria-invalid', 'true'); note.focus(); return;
  }
  const submitted = draft();
  busy = true; save.disabled = true; save.textContent = 'Saving…';
  form.setAttribute('aria-busy', 'true'); status.textContent = 'Saving…'; saveError.hidden = true;
  try {
    const result = await heartbeat.call('save_note', submitted);
    if (!receive(result)) throw new Error('Could not read the saved note. Your draft is still here.');
    dirty = JSON.stringify(draft()) !== JSON.stringify(submitted);
    if (!dirty) fill();
    status.textContent = dirty ? 'Saved. Your newer edits are not saved yet.' : 'Saved';
  } catch (error) {
    saveError.textContent = error.message || 'Could not save. Your draft is still here; try again.';
    saveError.hidden = false; status.textContent = 'Not saved';
  } finally {
    busy = false; save.disabled = false; save.textContent = 'Save note'; form.removeAttribute('aria-busy');
  }
});
window.addEventListener('heartbeat:data', event => receive(event.detail));
receive(heartbeat.data);
</script>
</html>
```

## Adapt and inspect

Use the page structure only where it fits the task. A single form can omit navigation; a dense workflow can use a wider main/detail layout. Customize decoration and accent choices without losing readable text, hierarchy or control states. The helper and error rows belong with their field. Selected navigation uses a persistent border, fill and weight as well as `aria-current`; these are ordinary navigation buttons, not ARIA tabs with an unimplemented keyboard contract.

Inspect the rendered page at narrow and wide widths, with long localized labels, both host themes and increased text scale. Confirm that the active view alone is visible (`hidden` must not be overridden by a `display` rule), the close/expand overlay leaves the heading usable, and the final action is reachable through the single page scroll. Check default/focus/invalid/disabled/pending controls and primary/secondary contrast. Feedback tokens such as `--hb-danger` are suitable for borders/icons; they are not automatically readable as small text on every background.

Exercise empty validation, a failed save, a successful save, switching views with an unsaved draft, an owner-data refresh while typing, and editing during an in-flight save. Then close/reopen in the real host to verify persistence and retained state. Standalone rendering or a mocked bridge can verify layout and local interaction, but cannot prove actual owner persistence.
