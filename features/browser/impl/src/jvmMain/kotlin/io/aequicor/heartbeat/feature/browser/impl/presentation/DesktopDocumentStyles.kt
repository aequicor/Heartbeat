package io.aequicor.heartbeat.feature.browser.impl.presentation

/** Text-like inputs retain native editing, including date pickers and number steppers. */
private const val FIELDS = """
input:not([type=hidden i],[type=file i],[type=checkbox i],[type=radio i],[type=range i],
  [type=color i],[type=image i],[type=button i],[type=submit i],[type=reset i]),select,textarea
"""

private const val BUTTONS = "button,input[type=button i],input[type=submit i],input[type=reset i]"

/**
 * A neutral host baseline for self-contained pages. Zero-specificity element selectors let authored CSS win;
 * pseudo-elements add only their own specificity. No forced width, display, appearance or page/navigation layout.
 * Specialized input types keep their browser geometry; native focus and disabled semantics remain functional.
 */
internal val desktopDocumentStyles: String = """
:where(*), :where(*)::before, :where(*)::after { box-sizing: border-box; }
:where(body) {
  margin: 0;
  background: var(--hb-surface);
  color: var(--hb-text);
  font-family: var(--hb-font-family, system-ui, sans-serif);
  font-size: var(--hb-font-size);
  line-height: var(--hb-line-height);
}
:where(button, input, select, textarea) { font: inherit; color: inherit; }
:where($FIELDS) {
  min-inline-size: 0;
  max-inline-size: 100%;
  min-block-size: var(--hb-control-height);
  padding: var(--hb-space-xs) var(--hb-space-m);
  border: 0;
  border-radius: var(--hb-field-radius);
  background-color: var(--hb-input);
  color: var(--hb-text);
  box-shadow: none;
}
:where(textarea) { resize: vertical; }
:where(input, textarea)::placeholder { color: var(--hb-text-muted); opacity: 1; }
:where($BUTTONS) {
  min-block-size: var(--hb-control-height);
  padding: var(--hb-space-xs) var(--hb-space-m);
  border: 0;
  border-radius: var(--hb-control-radius);
  background-color: var(--hb-control);
  color: var(--hb-text);
  cursor: pointer;
}
:where(:is($BUTTONS):enabled:hover) { background-color: var(--hb-control-hover); }
:where(:is($BUTTONS):enabled:active) { background-color: var(--hb-control-pressed); }
:where(input[type=checkbox i], input[type=radio i], input[type=range i]) { accent-color: var(--hb-accent); }
:where(:is($FIELDS):focus, button:focus-visible, input:focus-visible, select:focus-visible, textarea:focus-visible) {
  outline: var(--hb-focus-width) solid var(--hb-focus);
  outline-offset: var(--hb-focus-offset);
}
:where(button:disabled, input:disabled, select:disabled, textarea:disabled) {
  color: var(--hb-text-muted);
  cursor: default;
}
""".trimIndent()
