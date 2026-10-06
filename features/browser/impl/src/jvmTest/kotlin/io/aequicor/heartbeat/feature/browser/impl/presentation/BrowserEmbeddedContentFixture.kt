package io.aequicor.heartbeat.feature.browser.impl.presentation

/** Reports success only after real child scripts, images and a rejected subresource redirect complete. */
internal fun browserEmbeddedContentFixture(): String = """
    <!doctype html><html><head><title>embedded-pending</title></head><body>
    <script>
    const completed = new Set();
    function done(name) {
        completed.add(name);
        if (completed.size === 6) document.title = 'embedded-ready';
    }
    function childPage(name) {
        return '<script>parent.postMessage("' + name + '","*");<' + '/script>';
    }
    const dataFrame = document.createElement('iframe');
    const blobFrame = document.createElement('iframe');
    window.addEventListener('message', event => {
        if (event.source === dataFrame.contentWindow && event.data === 'data-frame') done('data-frame');
        if (event.source === blobFrame.contentWindow && event.data === 'blob-frame') done('blob-frame');
    });
    dataFrame.sandbox = 'allow-scripts';
    dataFrame.src = 'data:text/html,' + encodeURIComponent(childPage('data-frame'));
    blobFrame.sandbox = 'allow-scripts';
    blobFrame.src = URL.createObjectURL(new Blob([childPage('blob-frame')], {type: 'text/html'}));
    document.body.append(dataFrame, blobFrame);
    const blankFrame = document.createElement('iframe');
    blankFrame.sandbox = '';
    blankFrame.onload = () => done('blank-frame');
    blankFrame.src = 'about:blank';
    document.body.append(blankFrame);
    const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"><rect width="1" height="1"/></svg>';
    function image(name, url) {
        const element = document.createElement('img');
        element.onload = () => done(name);
        element.onerror = () => { document.title = 'failed-' + name; };
        element.src = url;
        document.body.append(element);
    }
    image('data-image', 'data:image/svg+xml,' + encodeURIComponent(svg));
    image('blob-image', URL.createObjectURL(new Blob([svg], {type: 'image/svg+xml'})));
    const blocked = document.createElement('img');
    blocked.onload = () => { document.title = 'unexpected-local-resource'; };
    blocked.onerror = () => done('blocked-resource');
    blocked.src = '/blocked-resource';
    document.body.append(blocked);
    </script></body></html>
    """.trimIndent()
