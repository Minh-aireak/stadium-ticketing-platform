import { describe, expect, it } from 'vitest'

import { PASSWORD_REQUIREMENTS } from './passwordPolicy'
import { passwordSchema } from './registerSchema'

/**
 * identity-service's RawPassword.validate is the authority both files claim to mirror:
 *
 *   password.length() >= 8
 *   password.chars().anyMatch(Character::isUpperCase)
 *   password.chars().anyMatch(Character::isLowerCase)
 *   password.chars().anyMatch(Character::isDigit)
 *
 * All three Character predicates are Unicode-aware — isUpperCase is "general category
 * UPPERCASE_LETTER, or the contributory property Other_Uppercase", and isDigit is category
 * DECIMAL_DIGIT_NUMBER. The frontend spelled them /[A-Z]/, /[a-z]/ and /[0-9]/, which is ASCII
 * only, so it rejected passwords the backend accepts — on a Vietnamese-language product, where
 * a name like "Ánh" in a password is entirely ordinary.
 */
const CASES: { password: string; backendAccepts: boolean; why: string }[] = [
  { password: 'Nguyen123', backendAccepts: true, why: 'plain ASCII, accepted everywhere' },
  { password: 'Ánhxinh1', backendAccepts: true, why: 'its only uppercase letter is Á (U+00C1)' },
  { password: 'Đường12abc', backendAccepts: true, why: 'its only uppercase letter is Đ (U+0110)' },
  { password: 'мойПароль1', backendAccepts: true, why: 'Cyrillic П is an uppercase letter' },
  // Above U+FFFF. RawPassword walked the password with String.chars(), which yields UTF-16 code
  // units, so both of these reached Character.isUpperCase / isDigit as lone surrogates and were
  // rejected; it walks codePoints() now. Neither row moves on this side — the frontend has
  // accepted them since the patterns became Unicode properties. They are here because a parity
  // table with no supplementary-plane row is what let the two sides drift apart unnoticed.
  { password: '\u{1E900}bcdefg1', backendAccepts: true, why: 'Adlam capital alif U+1E900 is an uppercase letter' },
  { password: 'Abcdefgh\u{1D7CF}', backendAccepts: true, why: 'mathematical bold digit one U+1D7CF is category Nd' },
  { password: 'ánhxinh12', backendAccepts: false, why: 'no uppercase letter at all' },
  { password: 'ABCDEFGH1', backendAccepts: false, why: 'no lowercase letter' },
  { password: 'Abcdefgh', backendAccepts: false, why: 'no digit' },
  { password: 'Abcdef1', backendAccepts: false, why: 'seven characters' },
  { password: '', backendAccepts: false, why: 'empty' },
]

describe('password policy parity with identity-service', () => {
  it.each(CASES)('$password — backend accepts: $backendAccepts ($why)', ({ password, backendAccepts }) => {
    expect(passwordSchema.safeParse(password).success).toBe(backendAccepts)
  })

  /**
   * The checklist under the password field and the schema that gates submit must answer the same
   * way, or the form tells the customer every box is ticked and then refuses to submit. The
   * 'special' row is advisory — the backend has no special-character rule — so it is excluded
   * here for the same reason it is excluded from the schema.
   */
  it.each(CASES)('checklist agrees with the schema for $password', ({ password, backendAccepts }) => {
    const gating = PASSWORD_REQUIREMENTS.filter((requirement) => requirement.id !== 'special')
    expect(gating.every((requirement) => requirement.test(password))).toBe(backendAccepts)
  })

  it('never lets the advisory special-character row block submission', () => {
    const noSpecial = 'Nguyen123'
    expect(PASSWORD_REQUIREMENTS.find((r) => r.id === 'special')?.test(noSpecial)).toBe(false)
    expect(passwordSchema.safeParse(noSpecial).success).toBe(true)
  })
})

describe('passwordSchema error messages', () => {
  it('still names the empty field before anything else', () => {
    const result = passwordSchema.safeParse('')
    expect(result.success).toBe(false)
    if (!result.success) expect(result.error.issues[0].message).toBe('Vui lòng nhập mật khẩu')
  })

  it('still reports the character class that is missing', () => {
    const result = passwordSchema.safeParse('abcdefgh1')
    expect(result.success).toBe(false)
    if (!result.success) {
      expect(result.error.issues.map((issue) => issue.message)).toContain(
        'Mật khẩu phải có ít nhất 1 chữ hoa',
      )
    }
  })
})
