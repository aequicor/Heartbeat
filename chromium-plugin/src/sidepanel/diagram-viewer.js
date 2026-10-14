// @ts-check
import { createTranslator } from '../shared/strings.js';
import { DiagramUnavailableError, receiveDiagram } from './diagram-transfer.js';

/**
 * Shows one local PNG with fit/actual-size/zoom controls. The object URL belongs to the viewer, not the panel,
 * and is revoked when this page closes. All titles and messages use textContent; no diagram markup is run.
 * @param {any} chrome
 * @param {Document} document
 * @param {Window} window
 * @param {Pick<typeof URL, 'createObjectURL' | 'revokeObjectURL'>} [urls]
 * @param {import('./diagram-transfer.js').Timers} [timers]
 */
export async function showDiagram(chrome, document, window, urls = URL, timers = globalThis) {
  const t = createTranslator(chrome.i18n);
  const image = /** @type {HTMLImageElement} */ (document.getElementById('diagram'));
  const viewport = /** @type {HTMLElement} */ (document.getElementById('viewport'));
  const status = /** @type {HTMLElement} */ (document.getElementById('status'));
  const controls = /** @type {HTMLElement} */ (document.getElementById('controls'));
  const heading = /** @type {HTMLElement} */ (document.getElementById('title'));
  const scaleLabel = /** @type {HTMLElement} */ (document.getElementById('scale'));
  const zoomIn = /** @type {HTMLButtonElement} */ (document.getElementById('zoom-in'));
  const zoomOut = /** @type {HTMLButtonElement} */ (document.getElementById('zoom-out'));
  const actual = /** @type {HTMLButtonElement} */ (document.getElementById('actual'));
  const fit = /** @type {HTMLButtonElement} */ (document.getElementById('fit'));
  document.documentElement.lang = chrome.i18n.getUILanguage();
  heading.textContent = t('diagramTitle');
  document.title = `${t('diagramTitle')} — Heartbeat`;
  status.textContent = t('diagramLoading');
  zoomIn.setAttribute('aria-label', t('diagramZoomIn'));
  zoomOut.setAttribute('aria-label', t('diagramZoomOut'));
  actual.title = t('diagramActualSize');
  fit.textContent = t('diagramFit');
  viewport.setAttribute('aria-label', t('diagramTitle'));
  /** @type {string | undefined} */
  let objectUrl;
  let closed = false;
  let fitted = true;
  let scale = 1;
  const transfer = new AbortController();
  const release = () => {
    closed = true;
    transfer.abort();
    if (objectUrl) urls.revokeObjectURL(objectUrl);
    objectUrl = undefined;
    image.removeAttribute('src');
  };
  window.addEventListener('pagehide', release, { once: true });
  /** @param {number} next */
  const resize = (next) => {
    scale = Math.max(0.01, Math.min(4, next));
    image.style.width = `${Math.round(image.naturalWidth * scale)}px`;
    image.style.height = `${Math.round(image.naturalHeight * scale)}px`;
    scaleLabel.textContent = `${Math.round(scale * 100)}%`;
    zoomOut.disabled = scale <= 0.01;
    zoomIn.disabled = scale >= 4;
  };
  const fitImage = () => resize(Math.min(1,
    viewport.clientWidth / image.naturalWidth, viewport.clientHeight / image.naturalHeight));
  try {
    await receiveDiagram(chrome, window.location.hash.slice(1), async (diagram) => {
      if (closed) return;
      heading.textContent = diagram.title || t('diagramTitle');
      document.title = `${heading.textContent} — Heartbeat`;
      image.alt = heading.textContent;
      viewport.style.backgroundColor = diagram.background;
      const bytes = Uint8Array.from(atob(diagram.png), (character) => character.charCodeAt(0));
      objectUrl = urls.createObjectURL(new Blob([bytes], { type: 'image/png' }));
      image.src = objectUrl;
      await image.decode();
      if (closed) return;
      status.hidden = true;
      controls.hidden = false;
      viewport.hidden = false;
      fitImage();
      zoomIn.addEventListener('click', () => { fitted = false; resize(scale * 1.25); });
      zoomOut.addEventListener('click', () => { fitted = false; resize(scale / 1.25); });
      actual.addEventListener('click', () => { fitted = false; resize(1); });
      fit.addEventListener('click', () => { fitted = true; fitImage(); });
      window.addEventListener('resize', () => { if (fitted && !closed) fitImage(); });
    }, { signal: transfer.signal, timers });
  } catch (error) {
    if (closed) return;
    release();
    status.textContent = error instanceof DiagramUnavailableError ? t('diagramUnavailable') : t('diagramFailed');
    console.warn('Heartbeat: diagram viewer could not load the image', error);
  }
}
