// @ts-check
import { createPanelHost } from './panel-host.js';

// Runs before the deferred wasm bundle (`app/panel.js`), which reads the host on start.
/** @type {any} */ (globalThis).heartbeatPanel = createPanelHost(chrome);
