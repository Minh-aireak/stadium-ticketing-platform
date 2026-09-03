export const PASSWORD_MIN_LENGTH = 8

/**
 * The character classes identity-service enforces, spelled the way it spells them.
 *
 * <p>RawPassword.validate walks the password with codePoints() and asks Character.isUpperCase /
 * isLowerCase / isDigit, and all three are Unicode-aware: isUpperCase is "general category
 * UPPERCASE_LETTER, or the contributory property Other_Uppercase" — which is exactly the Unicode
 * binary property Uppercase — and isDigit is general category DECIMAL_DIGIT_NUMBER, which is Nd.
 * Written as /[A-Z]/, /[a-z]/ and /[0-9]/ the frontend was strictly stricter than the service it
 * claims to mirror, and rejected ordinary passwords on a Vietnamese-language product: "Ánhxinh1"
 * has no character in A-Z.
 *
 * <p>codePoints() matters to the mirror: while validate used chars() it fed those predicates
 * UTF-16 code units, so the two sides disagreed about every character above U+FFFF.
 *
 * <p>Exported so PASSWORD_REQUIREMENTS (the checklist) and passwordSchema (the gate) cannot
 * drift apart again — before this they were two independent copies of the same three rules.
 */
export const UPPERCASE_PATTERN = /\p{Uppercase}/u
export const LOWERCASE_PATTERN = /\p{Lowercase}/u
export const DIGIT_PATTERN = /\p{Nd}/u

/**
 * "Special" means neither letter nor number, not "outside A-Za-z0-9". The ASCII negation ticked
 * the special-character row for "Ánhxinh1", whose only non-ASCII character is a letter. Advisory
 * either way — identity-service has no special-character rule — but it also feeds the strength
 * score's variety count, which was counting an accented letter as a second character class.
 */
export const SPECIAL_PATTERN = /[^\p{L}\p{N}]/u

export interface PasswordRequirement {
  id: string
  label: string
  test: (password: string) => boolean
}

/**
 * The checklist rendered under a password field (see PasswordChecklist). What actually gates
 * submission is `passwordSchema` in registerSchema.ts; the four policy rows here run the same
 * rules through the same shared patterns, so a ticked checklist and an accepted form can never
 * disagree. 'special' is advisory only — identity-service's RawPassword has no special-character
 * rule, and the frontend must not reject a password the backend accepts.
 *
 * <p>The labels name no character range on purpose. They used to read "(A-Z)", "(a-z)" and
 * "(0-9)", which stopped being true the moment the rules became the Unicode properties
 * RawPassword actually tests: "Ánhxinh1" ticks the uppercase row and contains nothing in A-Z.
 *
 * <p>Both halves of this list used to carry a `required: boolean` documented as "Required
 * requirements gate form submission; non-required ones only affect the strength score". Nothing
 * anywhere read the field: submission is gated by zod, and getPasswordStrength computes its
 * score from its own character-class checks without consulting this list at all.
 */
export const PASSWORD_REQUIREMENTS: PasswordRequirement[] = [
  {
    id: 'length',
    label: `Ít nhất ${PASSWORD_MIN_LENGTH} ký tự`,
    test: (password) => password.length >= PASSWORD_MIN_LENGTH,
  },
  {
    id: 'uppercase',
    label: 'Có chữ hoa',
    test: (password) => UPPERCASE_PATTERN.test(password),
  },
  {
    id: 'lowercase',
    label: 'Có chữ thường',
    test: (password) => LOWERCASE_PATTERN.test(password),
  },
  {
    id: 'digit',
    label: 'Có chữ số',
    test: (password) => DIGIT_PATTERN.test(password),
  },
  {
    id: 'special',
    label: 'Có ký tự đặc biệt (khuyến khích)',
    test: (password) => SPECIAL_PATTERN.test(password),
  },
]

export type PasswordStrengthScore = 0 | 1 | 2 | 3 | 4

export interface PasswordStrengthResult {
  score: PasswordStrengthScore
  label: string
  colorClassName: string
}

const STRENGTH_LEVELS: Array<Omit<PasswordStrengthResult, 'score'>> = [
  { label: 'Rất yếu', colorClassName: 'bg-danger' },
  { label: 'Yếu', colorClassName: 'bg-danger' },
  { label: 'Trung bình', colorClassName: 'bg-warning' },
  { label: 'Mạnh', colorClassName: 'bg-accent' },
  { label: 'Rất mạnh', colorClassName: 'bg-primary' },
]

/** Heuristic 0-4 strength score based on length, character classes, and character diversity. */
export function getPasswordStrength(password: string): PasswordStrengthResult {
  if (password.length === 0) {
    return { score: 0, ...STRENGTH_LEVELS[0] }
  }

  const hasLower = LOWERCASE_PATTERN.test(password)
  const hasUpper = UPPERCASE_PATTERN.test(password)
  const hasDigit = DIGIT_PATTERN.test(password)
  const hasSpecial = SPECIAL_PATTERN.test(password)
  const variety = [hasLower, hasUpper, hasDigit, hasSpecial].filter(Boolean).length

  let points = 0
  if (password.length >= PASSWORD_MIN_LENGTH) points += 1
  if (password.length >= 12) points += 1
  if (hasLower) points += 1
  if (hasUpper) points += 1
  if (hasDigit) points += 1
  if (hasSpecial) points += 1
  if (variety >= 3) points += 1

  const maxPoints = 7
  const score = Math.min(4, Math.floor((points / maxPoints) * 4)) as PasswordStrengthScore
  return { score, ...STRENGTH_LEVELS[score] }
}
