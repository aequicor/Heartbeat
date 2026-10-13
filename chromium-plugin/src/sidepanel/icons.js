// @ts-check

/**
 * Outline icons of the Heartbeat design system (`HbIcons.kt`): the same path data in a 20×20 viewport, stroked at
 * 1.4 with round caps and joins, or filled. `tests/icons.test.js` keeps them equal to the app's.
 */
export const ICONS = Object.freeze({
  Plus: { path: 'M10,4 V16 M4,10 H16', filled: false },
  Search: { path: 'M14,8.5 A5.5,5.5 0,1 1,3,8.5 A5.5,5.5 0,1 1,14,8.5 Z M12.5,12.5 L17,17', filled: false },
  Close: { path: 'M5,5 L15,15 M15,5 L5,15', filled: false },
  Folder: { path: 'M2.5,7 V5 Q2.5,3.5 4,3.5 H7 L9,5.5 H16 Q17.5,5.5 17.5,7 V14.5 Q17.5,16 16,16 H4 Q2.5,16 2.5,14.5 Z M2.5,8.5 H17.5', filled: false },
  FolderOpen: { path: 'M2.5,8 V5 Q2.5,3.5 4,3.5 H7 L9,5.5 H15 Q16.5,5.5 16.5,7 V8 M2.5,8 H18 L15.5,16 H4 Z', filled: false },
  ChevronDown: { path: 'M5,7.5 L10,12.5 L15,7.5', filled: false },
  ChevronRight: { path: 'M7.5,5 L12.5,10 L7.5,15', filled: false },
  ChevronUp: { path: 'M5,12.5 L10,7.5 L15,12.5', filled: false },
  Pin: { path: 'M7,3 H13 L12,8 L15,11 V12 H5 V11 L8,8 Z M10,12 V18', filled: false },
  Unpin: { path: 'M3,3 L17,17 M9,3 H13 L12,8 M8,8 L5,11 V12 H12 M10,12 V18', filled: false },
  Lock: { path: 'M6,8 V6 A4,4 0,0 1,14,6 V8 M4,8 H16 V17.5 H4 Z M10,12 V14', filled: false },
  Archive: { path: 'M2.5,3 H17.5 V7 H2.5 Z M4,7 V17 H16 V7 M8,10 H12', filled: false },
  Undo: { path: 'M6.5,3.5 L3,7 L6.5,10.5 M3,7 H12 A4.5,4.5 0,0 1,12,16 H9', filled: false },
  More: { path: 'M4.7,10 A1.2,1.2 0,1 1,2.3,10 A1.2,1.2 0,1 1,4.7,10 Z M11.2,10 A1.2,1.2 0,1 1,8.8,10 A1.2,1.2 0,1 1,11.2,10 Z M17.7,10 A1.2,1.2 0,1 1,15.3,10 A1.2,1.2 0,1 1,17.7,10 Z', filled: true },
  ArrowUp: { path: 'M10,16 V4 M5,9 L10,4 L15,9', filled: false },
  ArrowLeft: { path: 'M16,10 H4 M9,5 L4,10 L9,15', filled: false },
  Stop: { path: 'M6,4.5 H14 Q15.5,4.5 15.5,6 V14 Q15.5,15.5 14,15.5 H6 Q4.5,15.5 4.5,14 V6 Q4.5,4.5 6,4.5 Z', filled: true },
  Layers: { path: 'M10,2.5 L17.5,6.5 L10,10.5 L2.5,6.5 Z M2.5,10.5 L10,14.5 L17.5,10.5 M2.5,14 L10,18 L17.5,14', filled: false },
  Sparkles: { path: 'M9,3 Q9.5,9.5 16,10 Q9.5,10.5 9,17 Q8.5,10.5 2,10 Q8.5,9.5 9,3 Z M15.5,2 V6 M13.5,4 H17.5', filled: false },
  Chats: { path: 'M6,3 H16 Q17,3 17,4 V11 Q17,12 16,12 H14 M3,7 H12 Q13,7 13,8 V14 Q13,15 12,15 H6 L3,18 V15 Q2,15 2,14 V8 Q2,7 3,7 Z', filled: false },
  Check: { path: 'M4,10 L8,14 L16,6', filled: false },
  FileText: { path: 'M5,2.5 H11 L16,7.5 V16 Q16,17.5 14.5,17.5 H5 Q3.5,17.5 3.5,16 V4 Q3.5,2.5 5,2.5 Z M11,2.5 V7.5 H16 M7,11 H12.5 M7,14 H11', filled: false },
  Edit: { path: 'M12,4 L16,8 M3,17 L4,12 L13,3 Q14,2 15,3 L17,5 Q18,6 17,7 L8,16 Z', filled: false },
  Shield: { path: 'M10,2 L17,5 V10 Q17,15 10,18 Q3,15 3,10 V5 Z M7,10 L9,12 L13,7', filled: false },
  Globe: { path: 'M17.5,10 A7.5,7.5 0,1 1,2.5,10 A7.5,7.5 0,1 1,17.5,10 Z M10,2.5 C5,6 5,14 10,17.5 C15,14 15,6 10,2.5 M2.5,10 H17.5', filled: false },
  Bookmark: { path: 'M5,3 H15 V18 L10,14.5 L5,18 Z', filled: false },
});

const SVG = 'http://www.w3.org/2000/svg';

/**
 * An inline icon colored by the text around it.
 * @param {Document} document
 * @param {keyof typeof ICONS} name
 * @param {number} [size] CSS pixels; 18 like the app's icons, 14 for small ones
 * @returns {SVGSVGElement}
 */
export function icon(document, name, size = 18) {
  const { path, filled } = ICONS[name];
  const svg = /** @type {SVGSVGElement} */ (document.createElementNS(SVG, 'svg'));
  svg.setAttribute('viewBox', '0 0 20 20');
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(size));
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('class', 'icon');
  const shape = document.createElementNS(SVG, 'path');
  shape.setAttribute('d', path);
  if (filled) {
    shape.setAttribute('fill', 'currentColor');
  } else {
    shape.setAttribute('fill', 'none');
    shape.setAttribute('stroke', 'currentColor');
    shape.setAttribute('stroke-width', '1.4');
    shape.setAttribute('stroke-linecap', 'round');
    shape.setAttribute('stroke-linejoin', 'round');
  }
  svg.append(shape);
  return svg;
}
