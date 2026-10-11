package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.normalizeBrowserUrl
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Chromium streams a bounded response in the tab's isolated world, preserving its credentials and
 * final URL.
 */
internal class DesktopBrowserDownload(private val evaluate: suspend (String) -> JsonElement) {
    suspend fun capture(address: String): JsonObject {
        val url = requireNotNull(normalizeBrowserUrl(address)) { "Invalid source URL" }
        return evaluate("($FETCH_SCRIPT)(${JsonPrimitive(url)})").jsonObject
    }

    private companion object {
        // No cross-origin fallback: the user must first open the original resource and unlock its
        // origin.
        const val FETCH_SCRIPT =
            """async (url) => {
            const original = new URL(url);
            if (original.origin !== location.origin) throw Error('Reopen the original source first');
            const response = await fetch(url, {credentials:'include', cache:'no-store'});
            const final = new URL(response.url);
            if (final.origin !== original.origin || !response.ok) {
                await response.body?.cancel(); throw Error('Source still unavailable');
            }
            const mime=(response.headers.get('content-type')||'').split(';')[0].trim().toLowerCase();
            if (mime==='text/html'||mime==='application/xhtml+xml') {
                await response.body?.cancel(); return {kind:'html',url:response.url};
            }
            const types={'application/pdf':'pdf','text/plain':'txt','text/markdown':'md'};
            if (!types[mime]) { await response.body?.cancel(); throw Error('Unsupported document'); }
            const limit=10000000;
            if (Number(response.headers.get('content-length'))>limit) {
                await response.body?.cancel(); throw Error('Document too large');
            }
            const reader=response.body.getReader(), chunks=[];
            let size=0;
            try {
                for (;;) {
                    const next=await reader.read(); if(next.done) break;
                    size+=next.value.length; if(size>limit) throw Error('Document too large');
                    chunks.push(next.value);
                }
            } finally { await reader.cancel(); }
            if(!size) throw Error('Empty document');
            const bytes=new Uint8Array(size); let offset=0;
            for(const chunk of chunks) {bytes.set(chunk,offset);offset+=chunk.length;}
            if(mime==='application/pdf' && new TextDecoder().decode(bytes.slice(0,5))!=='%PDF-') {
                throw Error('Invalid PDF');
            }
            let binary=''; for(let i=0;i<size;i+=8192) binary+=String.fromCharCode(...bytes.subarray(i,i+8192));
            return {kind:'document',url:response.url,mediaType:mime,extension:types[mime],contentBase64:btoa(binary)};
        }"""
    }
}
