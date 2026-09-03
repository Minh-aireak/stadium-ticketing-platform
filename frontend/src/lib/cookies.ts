// Reads a cookie by exact name.
//
// Parses rather than building a RegExp from `name`: interpolating a caller-supplied string into a
// pattern makes any regex metacharacter in it change what gets matched. Only 'XSRF-TOKEN' is
// passed today and it happens to be inert, but a name is data, not a pattern, and nothing about
// this function's signature says otherwise.
export function getCookie(name: string): string | null {
  for (const entry of document.cookie.split('; ')) {
    const separator = entry.indexOf('=')
    if (separator < 0) continue
    if (entry.slice(0, separator) === name) {
      return decodeURIComponent(entry.slice(separator + 1))
    }
  }
  return null
}
