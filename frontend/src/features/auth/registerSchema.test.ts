import { describe, expect, it } from 'vitest'

import { EMAIL_MAX_LENGTH, emailSchema } from './registerSchema'

/**
 * identity-service's Email value object is the authority this schema tracks. It now bounds the
 * address at 254 characters — the longest RFC 5321 permits, and what `accounts.email`
 * (VARCHAR(255)) can hold. Before that bound existed the column was the only thing enforcing a
 * length, and it did so by failing the INSERT: a 300-character address passed `@Email`, passed
 * `Email`'s own pattern, and became "value too long for type character varying(255)" at commit,
 * reported to the caller as 409 "The request conflicts with existing data".
 *
 * The rest of emailSchema is deliberately NOT a mirror — zod's `.email()` is stricter than
 * Email.java's pattern, which is the safe direction. The length bound has to be mirrored for the
 * same reason: without it the client would wave through an address the server rejects, which is
 * the unsafe direction.
 */
describe('emailSchema length bound', () => {
  const address = (length: number) => `${'a'.repeat(length - 12)}@example.com`

  /** Written without EMAIL_MAX_LENGTH so it says what the schema did before the bound existed. */
  it('rejects an address far longer than any column that stores it', () => {
    const wayTooLong = `${'a'.repeat(500)}@example.com`

    expect(emailSchema.safeParse(wayTooLong).success).toBe(false)
  })

  it('accepts an address of exactly the maximum length', () => {
    const value = address(EMAIL_MAX_LENGTH)

    expect(value).toHaveLength(EMAIL_MAX_LENGTH)
    expect(emailSchema.safeParse(value).success).toBe(true)
  })

  it('rejects an address one character longer than identity-service accepts', () => {
    const result = emailSchema.safeParse(address(EMAIL_MAX_LENGTH + 1))

    expect(result.success).toBe(false)
    expect(result.error?.issues[0].message).toContain(String(EMAIL_MAX_LENGTH))
  })

  it('measures length after trimming, as Email.java does', () => {
    const value = `  ${address(EMAIL_MAX_LENGTH)}  `

    expect(emailSchema.safeParse(value).success).toBe(true)
  })
})
