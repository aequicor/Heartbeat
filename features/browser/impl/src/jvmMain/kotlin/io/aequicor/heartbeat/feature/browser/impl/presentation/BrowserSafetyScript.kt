package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Bounded evidence from one isolated-world task, without mutating the read's element registry. */
internal fun browserSafetyScript(geometry: String, operation: BrowserOperation?, arguments: JsonObject): String =
    """
    (() => {
        const heartbeatSafetySnapshot = true;
        const args = $arguments;
        const operation = ${JsonPrimitive(operation?.name.orEmpty())};
        const view = $geometry;
        if (!window.__heartbeatSafetyNodes) {
            window.__heartbeatSafetyNodes = new WeakMap();
            window.__heartbeatSafetyNextNode = 0;
        }
        const describe = e => {
            if (!e) return null;
            if (!window.__heartbeatSafetyNodes.has(e)) {
                window.__heartbeatSafetyNodes.set(e, ++window.__heartbeatSafetyNextNode);
            }
            const r = e.getBoundingClientRect();
            const form = e.closest('form');
            const autocomplete = e.getAttribute('autocomplete') || '';
            const sensitive = e.type === 'password' || autocomplete.split(/\s+/)
                .some(token => /^(cc-|one-time-code${'$'})/i.test(token));
            const value = sensitive ? null : typeof e.value === 'string' ? e.value.slice(0,2000) : null;
            return {
                node:window.__heartbeatSafetyNodes.get(e),tag:e.tagName,role:e.getAttribute('role'),
                type:e.getAttribute('type'),name:e.getAttribute('name'),autocomplete,isSensitive:sensitive,
                label:(e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.innerText||'').slice(0,1000),
                href:e.href||null,value,isValueTruncated:!sensitive&&typeof e.value==='string'&&e.value.length>2000,
                isConnected:e.isConnected,isEditable:e.isContentEditable,isDisabled:!!e.disabled,
                isVisible:e.checkVisibility({checkOpacity:true,checkVisibilityCSS:true}),
                rect:{x:r.x,y:r.y,width:r.width,height:r.height},
                form:form?{action:form.action,method:form.method,text:(form.innerText||'').slice(0,2000)}:null
            };
        };
        const hit = (x,y) => Number.isFinite(x)&&Number.isFinite(y) ? document.elementFromPoint(x,y) : null;
        let target = null;
        let hitTarget = null;
        if (operation === 'Click' && Number.isInteger(args.element)) {
            target = window.__heartbeatElements?.[args.element];
            if (!target || !target.isConnected) throw Error('Stale element');
            const r = target.getBoundingClientRect();
            hitTarget = hit(r.x+r.width/2,r.y+r.height/2);
        } else if (['Click','Scroll','Drag'].includes(operation)) {
            target = hit(args.x,args.y);
            hitTarget = target;
        }
        const content = document.body?.innerText || '';
        const evidence = {
            view,title:document.title,content:content.slice(0,12000),isContentTruncated:content.length>12000,
            target:describe(target),hitTarget:describe(hitTarget),focus:describe(document.activeElement),
            destination:operation==='Drag'?describe(hit(args.toX,args.toY)):null
        };
        if (JSON.stringify(evidence).length > 48000) throw Error('Safety evidence exceeds the review limit');
        return evidence;
    })()
    """.trimIndent()
