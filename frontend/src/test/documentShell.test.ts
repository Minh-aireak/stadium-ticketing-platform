import { describe, expect, it } from 'vitest'

import indexHtml from '../../index.html?raw'

// index.html is the one file in the storefront no other test can reach: it is not a module, no
// test mounts it, and `vite build` copies it through without checking a single thing it claims.

// Written as a literal, not built from the map's keys: a `\b` inside a template literal is a
// backspace character, not a word boundary, and the pattern then matches nothing while every
// assertion built on it stays green.
const FONT_WEIGHT_CLASS = /\bfont-(thin|extralight|light|normal|medium|semibold|bold|extrabold|black)\b/g

const TAILWIND_FONT_WEIGHTS: Record<string, number> = {
  thin: 100,
  extralight: 200,
  light: 300,
  normal: 400,
  medium: 500,
  semibold: 600,
  bold: 700,
  extrabold: 800,
  black: 900,
}

const sources = import.meta.glob('/src/**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

function weightsUsedInSource(): number[] {
  const used = new Set<number>()
  for (const [file, source] of Object.entries(sources)) {
    if (file.includes('/test/')) continue
    for (const match of source.matchAll(FONT_WEIGHT_CLASS)) {
      used.add(TAILWIND_FONT_WEIGHTS[match[1]])
    }
  }
  return [...used].sort((a, b) => a - b)
}

describe('index.html', () => {
  it('declares the language the storefront is actually written in', () => {
    // Every user-visible string is Vietnamese and six Intl formatters hardcode 'vi-VN'; nothing
    // sets documentElement.lang at runtime, so this attribute is all a screen reader, a
    // hyphenation engine or a translate prompt has to go on.
    expect(indexHtml).toMatch(/<html lang="vi">/)
  })

  it('requests every font weight the app applies to the body font', () => {
    // --font-sans is 'Inter' and --font-heading is 'Sora', 'Inter' — Inter is both the body face
    // and the heading fallback, so a weight the app uses but never downloads is not a fallback,
    // it is a browser-synthesised fake bold.
    const interRequest = /family=Inter:wght@([\d;]+)/.exec(indexHtml)
    expect(interRequest, 'index.html no longer requests Inter from Google Fonts').not.toBeNull()

    const requested = new Set(interRequest![1].split(';').map(Number))
    // 400 is the inherited body weight, carried by no class at all.
    const needed = [400, ...weightsUsedInSource()]
    const missing = [...new Set(needed)].filter((weight) => !requested.has(weight)).sort((a, b) => a - b)

    expect(missing, 'font weights used in src/ but never downloaded').toEqual([])
  })

  it('finds the source tree it scans for those weights', () => {
    // The scan above is silently vacuous if the glob stops matching — an empty `used` set makes
    // every weight look covered.
    expect(Object.keys(sources).length).toBeGreaterThan(50)
    expect(weightsUsedInSource().length).toBeGreaterThan(0)
  })
})
