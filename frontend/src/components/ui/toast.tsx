import { cva, type VariantProps } from 'class-variance-authority'
import { CheckCircle2, Info, X, XCircle } from 'lucide-react'

import { cn } from '@/lib/utils'

const toastVariants = cva(
  'pointer-events-auto flex w-full items-start gap-3 rounded-xl border bg-surface p-4 shadow-lg',
  {
    variants: {
      variant: {
        success: 'border-primary/40',
        error: 'border-danger/40',
        info: 'border-border',
      },
    },
    defaultVariants: { variant: 'info' },
  },
)

const iconByVariant = { success: CheckCircle2, error: XCircle, info: Info } as const
const iconColorByVariant = {
  success: 'text-primary',
  error: 'text-danger',
  info: 'text-accent',
} as const

export interface ToastProps extends VariantProps<typeof toastVariants> {
  title: string
  description?: string
  onDismiss: () => void
}

export function Toast({ variant = 'info', title, description, onDismiss }: ToastProps) {
  const resolved = variant ?? 'info'
  const Icon = iconByVariant[resolved]

  return (
    <div className={cn(toastVariants({ variant }))} role="alert">
      <Icon className={cn('mt-0.5 size-5 shrink-0', iconColorByVariant[resolved])} />
      <div className="flex-1">
        <div className="text-sm font-medium">{title}</div>
        {description && <div className="text-sm text-muted">{description}</div>}
      </div>
      <button
        type="button"
        onClick={onDismiss}
        className="text-muted transition-colors hover:text-foreground"
        aria-label="Đóng thông báo"
      >
        <X className="size-4" />
      </button>
    </div>
  )
}
