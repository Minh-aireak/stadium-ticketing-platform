import { describe, expect, it } from 'vitest'

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
