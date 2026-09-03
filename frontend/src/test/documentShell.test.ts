import { describe, expect, it } from 'vitest'

import indexHtml from '../../index.html?raw'

// index.html is the one file in the storefront no other test can reach: it is not a module, no
// test mounts it, and `vite build` copies it through without checking a single thing it claims.

describe('index.html', () => {
  it('declares the language the storefront is actually written in', () => {
    // Every user-visible string is Vietnamese and six Intl formatters hardcode 'vi-VN'; nothing
    // sets documentElement.lang at runtime, so this attribute is all a screen reader, a
    // hyphenation engine or a translate prompt has to go on.
    expect(indexHtml).toMatch(/<html lang="vi">/)
  })
})
