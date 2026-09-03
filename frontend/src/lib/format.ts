// One formatter per currency code, built on first use. The module used to hold a single
// Intl.NumberFormat, which is how it came to be pinned to one currency: constructing one is
// expensive enough that nobody wanted to do it per render, and the cheap way out was to bake
// 'VND' in. A Map keeps the caching without the assumption.
const currencyFormatters = new Map<string, Intl.NumberFormat>()
const plainNumberFormatter = new Intl.NumberFormat('vi-VN')

function currencyFormatter(currency: string): Intl.NumberFormat | null {
  const cached = currencyFormatters.get(currency)
  if (cached) return cached
  try {
    // No maximumFractionDigits override: Intl already knows each currency's minor-unit count —
    // zero for đồng and yen, two for dollars and euro — and capping it at zero for everyone
    // rendered USD 12.99 as "13".
    const formatter = new Intl.NumberFormat('vi-VN', { style: 'currency', currency })
    currencyFormatters.set(currency, formatter)
    return formatter
  } catch {
    // Intl.NumberFormat throws RangeError on anything that is not a three-letter code. Currency
    // codes arrive over the network (booking.currency, showtime.currency), so a malformed one
    // has to degrade to a readable amount rather than take the page down.
    return null
  }
}

/**
 * Formats a money amount in the currency it is actually quoted in.
 *
 * <p>`currency` is required on purpose. match-catalog-service accepts any ^[A-Z]{3}$ for a
 * showtime and carries it through inventory, booking and payment — payment-service even has
 * dedicated handling for the zero-decimal ones — so every amount on this site has a currency
 * attached to it upstream, and making the parameter optional would just re-open the door to
 * dropping it.
 */
export function formatCurrency(amount: number, currency: string): string {
  const formatter = currencyFormatter(currency)
  if (formatter) return formatter.format(amount)
  return `${plainNumberFormatter.format(amount)} ${currency}`
}

const kickoffFormatter = new Intl.DateTimeFormat('vi-VN', {
  weekday: 'short',
  day: '2-digit',
  month: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
})

export function formatKickoff(iso: string): string {
  return kickoffFormatter.format(new Date(iso))
}
