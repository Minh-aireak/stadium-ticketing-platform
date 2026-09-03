export const PASSWORD_MIN_LENGTH = 8

export interface PasswordRequirement {
  id: string
  label: string
  test: (password: string) => boolean
}

/**
 * The checklist rendered under a password field (see PasswordChecklist). What actually gates
 * submission is `passwordSchema` in registerSchema.ts; the four policy rows here restate the
 * same rules for the reader, and 'special' is advisory only — identity-service's RawPassword has
 * no special-character rule, and the frontend must not reject a password the backend accepts.
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
    label: 'Có chữ hoa (A-Z)',
    test: (password) => /[A-Z]/.test(password),
  },
  {
    id: 'lowercase',
    label: 'Có chữ thường (a-z)',
    test: (password) => /[a-z]/.test(password),
  },
  {
    id: 'digit',
    label: 'Có số (0-9)',
    test: (password) => /[0-9]/.test(password),
  },
  {
    id: 'special',
    label: 'Có ký tự đặc biệt (khuyến khích)',
    test: (password) => /[^A-Za-z0-9]/.test(password),
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

  const hasLower = /[a-z]/.test(password)
  const hasUpper = /[A-Z]/.test(password)
  const hasDigit = /[0-9]/.test(password)
  const hasSpecial = /[^A-Za-z0-9]/.test(password)
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
