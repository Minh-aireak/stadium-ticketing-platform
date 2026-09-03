import { afterEach, describe, expect, it, vi } from 'vitest'

import { formatCurrency } from './format'

describe('formatCurrency', () => {
  it('renders đồng exactly as it always did', () => {
    // Not toBe on a literal: Intl separates the amount from the symbol with U+00A0.
    expect(formatCurrency(400000, 'VND')).toMatch(/^400\.000\s₫$/u)
  })

  /**
   * The regression. The formatter was a single module-level Intl.NumberFormat pinned to
   * `currency: 'VND'`, so every amount on the site rendered with the đồng symbol no matter what
   * currency the showtime was actually priced in — including the total on the confirm-and-pay
   * step. match-catalog-service accepts any ^[A-Z]{3}$ for a showtime (MatchController's
   * AddShowtimeRequest) and payment-service charges in it, so the currency is real data, not a
   * constant.
   */
  it('honours the currency it is handed', () => {
    const usd = formatCurrency(12.99, 'USD')
    expect(usd).toContain('US$')
    expect(usd).not.toContain('₫')
  })

  /**
   * The old formatter also pinned maximumFractionDigits: 0, which is right for đồng and wrong
   * for everything with minor units — USD 12.99 rendered as "13". Intl already knows each
   * currency's minor-unit count, so the cap is what has to go, not be made conditional.
   */
  it('keeps the minor units of a currency that has them', () => {
    expect(formatCurrency(12.99, 'USD')).toContain('12,99')
  })

  it('leaves a zero-decimal currency whole', () => {
    expect(formatCurrency(1500, 'JPY')).toContain('1.500')
    expect(formatCurrency(1500, 'JPY')).not.toContain('1.500,00')
  })

  /**
   * Intl.NumberFormat throws RangeError on a currency code that is not three letters, and these
   * codes arrive over the network (booking.currency, showtime.currency). A malformed one must
   * degrade to a readable amount, not take the page down with it.
   */
  it('falls back instead of throwing on a code Intl rejects', () => {
    expect(() => formatCurrency(1000, 'V')).not.toThrow()
    expect(formatCurrency(1000, 'V')).toContain('1.000')
  })
})

describe('formatKickoff', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.resetModules()
  })

  /**
   * The regression. `kickoffFormatter` is a module-level `Intl.DateTimeFormat('vi-VN', {...})`
   * with no `timeZone` option, so it renders in whatever zone happens to be the process default
   * at the moment the module is first imported — a CI runner, a developer's machine, or (were
   * this ever server-rendered) a container, none of which have anything to do with when a match
   * in a Vietnamese stadium actually kicks off. Every showtime this platform sells is in Vietnam
   * (see MatchController's stadium data and inventory.pricing.currency defaulting to VND), so
   * every kickoff has exactly one correct clock reading regardless of who is looking at the page
   * or from where — the same reasoning TransactionalEmailService's DISPLAY_ZONE javadoc gives for
   * pinning the confirmation email to Asia/Ho_Chi_Minh instead of the backend host's default.
   * Before the fix this module re-imported under two different process timezones renders the
   * SAME instant as two different clock times, neither of which says which zone it is.
   */
  it('renders the same kickoff instant identically regardless of the host process timezone', async () => {
    const instant = '2026-09-05T19:00:00Z' // 02:00 the next day in Vietnam (UTC+7)

    vi.stubEnv('TZ', 'UTC')
    vi.resetModules()
    const { formatKickoff: underUtc } = await import('./format')

    vi.stubEnv('TZ', 'America/Los_Angeles')
    vi.resetModules()
    const { formatKickoff: underLosAngeles } = await import('./format')

    vi.stubEnv('TZ', 'Australia/Sydney')
    vi.resetModules()
    const { formatKickoff: underSydney } = await import('./format')

    const results = [underUtc(instant), underLosAngeles(instant), underSydney(instant)]
    expect(new Set(results).size).toBe(1)
  })

  it('reads as the Vietnam-local kickoff time no matter which zone the module loaded under', async () => {
    vi.stubEnv('TZ', 'Pacific/Kiritimati') // UTC+14 — as far from Vietnam's UTC+7 as the IANA database goes
    vi.resetModules()
    const { formatKickoff } = await import('./format')

    // 2026-09-05T19:00:00Z is 02:00 on 2026-09-06 in Asia/Ho_Chi_Minh (UTC+7).
    expect(formatKickoff('2026-09-05T19:00:00Z')).toBe('02:00 CN, 06/09')
  })
})
