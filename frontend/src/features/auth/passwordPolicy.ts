export const PASSWORD_MIN_LENGTH = 8

export interface PasswordRequirement {
  id: string
  label: string
  /** Required requirements gate form submission; non-required ones only affect the strength score. */
  required: boolean
  test: (password: string) => boolean
}

// Mirrors backend/identity-service RawPassword policy exactly: min 8 chars, at least one
// uppercase, one lowercase, one digit. The backend has no special-character rule, so it stays
// non-required here to avoid the frontend rejecting passwords the backend would accept.
export const PASSWORD_REQUIREMENTS: PasswordRequirement[] = [
  {
    id: 'length',
    label: `Ít nhất ${PASSWORD_MIN_LENGTH} ký tự`,
    required: true,
    test: (password) => password.length >= PASSWORD_MIN_LENGTH,
  },
  {
    id: 'uppercase',
    label: 'Có chữ hoa (A-Z)',
    required: true,
    test: (password) => /[A-Z]/.test(password),
  },
  {
    id: 'lowercase',
    label: 'Có chữ thường (a-z)',
    required: true,
    test: (password) => /[a-z]/.test(password),
  },
  {
    id: 'digit',
    required: true,
    label: 'Có số (0-9)',
    test: (password) => /[0-9]/.test(password),
  },
  {
    id: 'special',
    label: 'Có ký tự đặc biệt (khuyến khích)',
    required: false,
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
