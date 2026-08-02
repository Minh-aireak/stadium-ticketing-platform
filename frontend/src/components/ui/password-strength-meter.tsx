import { getPasswordStrength } from '@/features/auth/passwordPolicy'
import { memoComponent } from '@/lib/memo'

interface PasswordStrengthMeterProps {
  password: string
}

function PasswordStrengthMeter({ password }: PasswordStrengthMeterProps) {
  if (password.length === 0) return null

  const { score, label, colorClassName } = getPasswordStrength(password)
  const percent = ((score + 1) / 5) * 100

  return (
    <div className="flex flex-col gap-1" aria-live="polite">
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-surface-2">
        <div
          className={`h-full rounded-full transition-all duration-300 ${colorClassName}`}
          style={{ width: `${percent}%` }}
        />
      </div>
      <p className="text-xs text-muted">
        Độ mạnh mật khẩu: <span className="font-medium text-foreground">{label}</span>
      </p>
    </div>
  )
}

const MemoizedPasswordStrengthMeter = memoComponent(PasswordStrengthMeter)

export { MemoizedPasswordStrengthMeter as PasswordStrengthMeter }
