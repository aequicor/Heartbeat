package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.serialization.json.JsonPrimitive

/** Private presentation channel and the generation of the served page, never the owner-call router. */
internal data class DesktopDocumentLayoutScript(val query: String, val generation: Long)

/**
 * Observes intrinsic body content, not documentElement.scrollHeight (which is floored by the current viewport and
 * cannot shrink). A dedicated `[data-heartbeat-root]` may opt out of body decoration; otherwise body is measured.
 * The host rescales the reported CSS viewport width to its logical width, including non-default screen density.
 */
internal fun desktopDocumentLayoutBootstrap(layout: DesktopDocumentLayoutScript?): String = layout?.let {
    """
(() => {
  const query = window[${JsonPrimitive(it.query).scriptLiteral()}];
  if (typeof query !== 'function') return;
  const style = document.createElement('style');
  style.textContent = ':where(body){display:flow-root}html{scrollbar-gutter:stable}';
  (document.head || document.documentElement).appendChild(style);
  let scheduled = false;
  let previous = '';
  const measure = () => {
    scheduled = false;
    const root = document.querySelector('[data-heartbeat-root]') || document.body;
    if (!root || !window.innerWidth) return;
    const css = getComputedStyle(root);
    const height = Math.max(1, Math.ceil(root.getBoundingClientRect().height +
      (parseFloat(css.marginTop) || 0) + (parseFloat(css.marginBottom) || 0)));
    const report = '${it.generation}:' + window.innerWidth + ':' + height;
    if (report === previous) return;
    previous = report;
    query({ request: report, persistent: false, onSuccess: () => {}, onFailure: () => {} });
  };
  const schedule = () => {
    if (!scheduled) { scheduled = true; requestAnimationFrame(measure); }
  };
  const start = () => {
    const observer = new ResizeObserver(schedule);
    observer.observe(document.body);
    const root = document.querySelector('[data-heartbeat-root]');
    if (root) observer.observe(root);
    window.addEventListener('resize', schedule);
    window.addEventListener('heartbeat:data', schedule);
    window.addEventListener('heartbeat:theme', schedule);
    schedule();
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true });
  else start();
})();
    """.trimIndent()
}.orEmpty()
