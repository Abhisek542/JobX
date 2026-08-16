/**
 * The icon set, as data. Every glyph is lifted from the frozen mockup so the
 * app's line weight and shapes stay identical to the approved design.
 *
 * Kept as structured shapes rather than raw markup so nothing has to go through
 * innerHTML / a sanitizer bypass.
 */
export type IconShape =
  | { k: 'path'; d: string }
  | { k: 'circle'; cx: number; cy: number; r: number }
  | { k: 'rect'; x: number; y: number; w: number; h: number; rx?: number }
  | { k: 'polyline'; points: string }
  | { k: 'polygon'; points: string };

export type IconName = keyof typeof ICONS;

export const ICONS = {
  home: [
    { k: 'path', d: 'M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z' },
    { k: 'path', d: 'M9 22V12h6v10' },
  ],
  briefcase: [
    { k: 'rect', x: 2, y: 7, w: 20, h: 14, rx: 2 },
    { k: 'path', d: 'M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16' },
  ],
  star: [
    {
      k: 'polygon',
      points: '12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2',
    },
  ],
  user: [
    { k: 'path', d: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2' },
    { k: 'circle', cx: 12, cy: 7, r: 4 },
  ],
  plus: [{ k: 'path', d: 'M12 5v14M5 12h14' }],
  check: [{ k: 'path', d: 'm5 12 5 5L20 7' }],
  'chevron-left': [{ k: 'path', d: 'M15 18l-6-6 6-6' }],
  'chevron-right': [{ k: 'polyline', points: '9 18 15 12 9 6' }],
  'chevron-down': [{ k: 'polyline', points: '6 9 12 15 18 9' }],
  search: [
    { k: 'circle', cx: 11, cy: 11, r: 8 },
    { k: 'path', d: 'm21 21-4.35-4.35' },
  ],
  x: [{ k: 'path', d: 'M18 6 6 18M6 6l12 12' }],
  sun: [
    { k: 'circle', cx: 12, cy: 12, r: 4 },
    {
      k: 'path',
      d: 'M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M6.3 17.7l-1.4 1.4M19.1 4.9l-1.4 1.4',
    },
  ],
  moon: [{ k: 'path', d: 'M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z' }],
  bookmark: [{ k: 'path', d: 'M19 21l-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z' }],
  'check-circle': [
    { k: 'circle', cx: 12, cy: 12, r: 10 },
    { k: 'path', d: 'm9 12 2 2 4-4' },
  ],
  'x-circle': [
    { k: 'circle', cx: 12, cy: 12, r: 10 },
    { k: 'path', d: 'm4.9 4.9 14.2 14.2' },
  ],
  'external-link': [
    { k: 'path', d: 'M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6' },
    { k: 'path', d: 'M15 3h6v6M10 14 21 3' },
  ],
  info: [
    { k: 'circle', cx: 12, cy: 12, r: 10 },
    { k: 'path', d: 'M12 16v-4M12 8h.01' },
  ],
  tag: [
    {
      k: 'path',
      d: 'M20.59 13.41 11 3.83A2 2 0 0 0 9.59 3.24H4a1 1 0 0 0-1 1v5.59a2 2 0 0 0 .59 1.41l9.58 9.59a2 2 0 0 0 2.83 0l4.59-4.59a2 2 0 0 0 0-2.83Z',
    },
    { k: 'circle', cx: 7.5, cy: 7.5, r: 1.5 },
  ],
  clock: [
    { k: 'circle', cx: 12, cy: 12, r: 10 },
    { k: 'polyline', points: '12 6 12 12 16 14' },
  ],
  menu: [{ k: 'path', d: 'M3 6h18M3 12h18M3 18h18' }],
  alert: [
    {
      k: 'path',
      d: 'M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z',
    },
    { k: 'path', d: 'M12 9v4M12 17h.01' },
  ],
  refresh: [
    { k: 'path', d: 'M23 4v6h-6M1 20v-6h6' },
    { k: 'path', d: 'M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4-4.64 4.36A9 9 0 0 1 3.51 15' },
  ],
  trash: [
    {
      k: 'path',
      d: 'M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6',
    },
  ],
  pause: [
    { k: 'rect', x: 6, y: 4, w: 4, h: 16, rx: 1 },
    { k: 'rect', x: 14, y: 4, w: 4, h: 16, rx: 1 },
  ],
  play: [{ k: 'polygon', points: '6 3 20 12 6 21 6 3' }],
  'log-out': [
    { k: 'path', d: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4' },
    { k: 'path', d: 'M16 17l5-5-5-5M21 12H9' },
  ],
  mail: [
    { k: 'rect', x: 2, y: 4, w: 20, h: 16, rx: 2 },
    { k: 'path', d: 'm22 6-10 7L2 6' },
  ],
  lock: [
    { k: 'rect', x: 3, y: 11, w: 18, h: 11, rx: 2 },
    { k: 'path', d: 'M7 11V7a5 5 0 0 1 10 0v4' },
  ],
  sliders: [{ k: 'path', d: 'M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6' }],
  inbox: [
    { k: 'path', d: 'M22 12h-6l-2 3h-4l-2-3H2' },
    {
      k: 'path',
      d: 'M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z',
    },
  ],
} as const satisfies Record<string, readonly IconShape[]>;
