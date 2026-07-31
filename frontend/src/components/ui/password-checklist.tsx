import { Check, X } from 'lucide-react'

import { PASSWORD_REQUIREMENTS } from '@/features/auth/passwordPolicy'
import { cn } from '@/lib/utils'

interface PasswordChecklistProps {
  password: string
}

function PasswordChecklist({ password }: PasswordChecklistProps) {
  return (
    <ul className="flex flex-col gap-1" aria-live="polite">
      {PASSWORD_REQUIREMENTS.map((requirement) => {
        const met = requirement.test(password)
        return (
          <li
            key={requirement.id}
            className={cn('flex items-center gap-1.5 text-xs', met ? 'text-primary' : 'text-muted')}
          >
            {met ? <Check className="size-3.5 shrink-0" /> : <X className="size-3.5 shrink-0" />}
            {requirement.label}
          </li>
        )
      })}
    </ul>
  )
}

export { PasswordChecklist }
