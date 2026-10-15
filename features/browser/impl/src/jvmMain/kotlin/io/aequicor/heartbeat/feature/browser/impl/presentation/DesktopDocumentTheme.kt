package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Host presentation values only; application data and method permissions are separate. */
internal data class DesktopDocumentTheme(val isDark: Boolean, val variables: Map<String, String>) {
    fun json(): JsonObject = buildJsonObject {
        put("colorScheme", if (isDark) "dark" else "light")
        put("variables", JsonObject(variables.mapValues { JsonPrimitive(it.value) }))
    }
}

/** Updates colors and fonts in-place, retaining the page's controls, scroll position and in-flight calls. */
internal fun desktopDocumentThemeScript(theme: DesktopDocumentTheme): String =
    "window.__heartbeatTheme && window.__heartbeatTheme(${theme.json().scriptLiteral()});"

/** Page bootstrap, evaluated before its own scripts. Low-specificity defaults remain overridable by app CSS. */
internal fun desktopDocumentThemeBootstrap(theme: DesktopDocumentTheme?): String = """
  let theme = ${theme?.json().scriptLiteral()};
  const applyTheme = (next) => {
    if (!next) return;
    theme = Object.freeze({ colorScheme: next.colorScheme, variables: Object.freeze({ ...next.variables }) });
    const root = document.documentElement;
    root.style.colorScheme = theme.colorScheme;
    for (const [name, value] of Object.entries(theme.variables)) root.style.setProperty(name, value);
  };
  applyTheme(theme);
  Object.defineProperty(api, 'theme', { get: () => theme, enumerable: true });
  Object.defineProperty(window, '__heartbeatTheme', { value: (next) => {
    applyTheme(next);
    window.dispatchEvent(new CustomEvent('heartbeat:theme', { detail: theme }));
  } });
  const defaults = document.createElement('style');
  defaults.textContent = ${JsonPrimitive(desktopDocumentStyles).scriptLiteral()};
  (document.head || document.documentElement).appendChild(defaults);
    """.trimIndent()
