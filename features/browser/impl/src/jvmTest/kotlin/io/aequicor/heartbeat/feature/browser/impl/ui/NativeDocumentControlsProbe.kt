package io.aequicor.heartbeat.feature.browser.impl.ui

import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real computed CSS from the production bootstrap, included in both light and dark presentation reports. */
internal fun verifyNativeDocumentControls(report: JsonObject) {
    val controls = report.getValue("controls").jsonObject
    val body = controls.getValue("body").jsonObject
    assertEquals("19.5px", body.value("fontSize"), "Controls must honor the host's 150% text scale")
    for (id in listOf("entry", "select", "textarea", "button", "submit")) {
        val control = controls.getValue(id).jsonObject
        assertEquals(body.value("fontSize"), control.value("fontSize"), "$id font")
        assertEquals(body.value("fontFamily"), control.value("fontFamily"), "$id font family")
        assertEquals("32px", control.value("minHeight"), "$id minimum height")
        assertEquals("4px", control.value("paddingTop"), "$id padding")
        assertEquals("8px", control.value("paddingLeft"), "$id padding")
        assertEquals("0px", control.value("borderTopWidth"), "$id flat border")
        val field = id !in setOf("button", "submit")
        assertEquals(if (field) "8px" else "6px", control.value("borderTopLeftRadius"), "$id radius")
        assertEquals(controls.value(if (field) "inputColor" else "buttonColor"), control.value("backgroundColor"))
    }
    assertEquals(controls.value("mutedColor"), controls.getValue("disabled").jsonObject.value("color"))
    assertEquals(controls.value("mutedColor"), controls.getValue("placeholder").jsonObject.value("color"))
    assertEquals("none", controls.getValue("hidden-input").jsonObject.value("display"))
    assertEquals("none", controls.getValue("hidden-button").jsonObject.value("display"))
    for (id in listOf("checkbox", "radio", "range", "color", "file")) {
        val control = controls.getValue(id).jsonObject
        assertEquals("0px", control.value("minHeight"), "$id must retain native geometry")
        // Chromium's file input is a container with appearance:none; its upload button owns native appearance.
        if (id != "file") assertNotEquals("none", control.value("appearance"), "$id must retain native interaction")
    }
    val custom = controls.getValue("custom").jsonObject
    assertEquals("0px", custom.value("borderTopLeftRadius"), "Authored CSS must override the host baseline")
    assertEquals(controls.value("accentColor"), custom.value("backgroundColor"))
    assertTrue(controls.value("inputHeight").toFloat() >= body.value("lineHeight").removeSuffix("px").toFloat())
}

/** Activates only Chromium's focus model: the OS may keep any other window foreground during this CSS check. */
internal fun verifyNativeDocumentControlFocus(panel: DesktopDocumentPanel) {
    val client = assertNotNull(nativeEdt { documentBrowser(panel).devToolsClient })
    client.executeDevToolsMethod("Emulation.setFocusEmulationEnabled", """{"enabled":true}""").awaitNative()
    try {
        val response = client.executeDevToolsMethod(
            "Runtime.evaluate",
            buildJsonObject {
                put(
                    "expression",
                    "entry.focus({preventScroll:true}); ({focused:entry.matches(':focus'), controls:controlStyles()})",
                )
                put("returnByValue", true)
            }.toString(),
        ).awaitNative()
        val result = Json.parseToJsonElement(response).jsonObject.getValue("result").jsonObject
            .getValue("value").jsonObject
        assertEquals(JsonPrimitive(true), result["focused"], "The actual DOM control must match :focus")
        val controls = result.getValue("controls").jsonObject
        val focused = controls.getValue("entry").jsonObject
        assertEquals("solid", focused.value("outlineStyle"))
        assertEquals("2px", focused.value("outlineWidth"))
        assertEquals("2px", focused.value("outlineOffset"))
        assertEquals(controls.value("focusColor"), focused.value("outlineColor"))
    } finally {
        client.executeDevToolsMethod("Emulation.setFocusEmulationEnabled", """{"enabled":false}""").awaitNative()
    }
}

private fun JsonObject.value(name: String): String = getValue(name).jsonPrimitive.content

/** Out of normal flow so the presentation fixture can keep checking intrinsic height independently. */
internal val nativeDocumentControlsMarkup: String = """
<style>.custom-control { border-radius: 0; background-color: var(--hb-accent); }</style>
<section style="position:absolute;top:140px;left:0;max-width:100%">
  <select id="select"><option>Choice</option></select><textarea id="textarea" rows="2">Notes</textarea>
  <input id="placeholder-input" placeholder="Enter a value">
  <button id="button">Action</button><input id="submit" type="submit" value="Save">
  <button id="custom" class="custom-control">Custom action</button><button id="disabled" disabled>Unavailable</button>
  <input id="checkbox" type="checkbox"><input id="radio" type="radio"><input id="range" type="range">
  <input id="color" type="color"><input id="file" type="file"><input id="hidden-input" type="hidden">
  <button id="hidden-button" hidden>Hidden</button>
</section>
""".trimIndent()

/** Reads computed properties, never duplicates the implementation's CSS selectors. */
internal val nativeDocumentControlsScript: String = """
function controlStyles() {
  const read = (element, pseudo) => {
    const css = getComputedStyle(element, pseudo);
    return Object.fromEntries(['fontSize', 'fontFamily', 'lineHeight', 'minHeight', 'paddingTop', 'paddingLeft',
      'borderTopWidth', 'borderTopLeftRadius', 'backgroundColor', 'color', 'outlineStyle', 'outlineWidth',
      'outlineColor', 'outlineOffset', 'display', 'appearance'].map(name => [name, css[name]]));
  };
  const color = (name) => {
    const sample = document.createElement('span');
    sample.style.color = 'var(' + name + ')';
    document.body.append(sample);
    const result = getComputedStyle(sample).color;
    sample.remove();
    return result;
  };
  const ids = ['entry', 'select', 'textarea', 'button', 'submit', 'custom', 'disabled', 'checkbox', 'radio',
    'range', 'color', 'file', 'hidden-input', 'hidden-button'];
  return { ...Object.fromEntries(ids.map(id => [id, read(document.getElementById(id))])),
    body: read(document.body), placeholder: read(document.getElementById('placeholder-input'), '::placeholder'),
    inputColor: color('--hb-input'), buttonColor: color('--hb-control'), accentColor: color('--hb-accent'),
    focusColor: color('--hb-focus'), mutedColor: color('--hb-text-muted'),
    inputHeight: entry.getBoundingClientRect().height };
}
""".trimIndent()
