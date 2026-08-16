import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { motion } from 'framer-motion'
import { CheckCircle2, LinkIcon, ShieldAlert } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { Link, useSearchParams } from 'react-router-dom'

import { resetPassword } from '@/features/auth/authApi'
import {
  resetPasswordFormSchema,
  type ResetPasswordFormValues,
} from '@/features/auth/passwordResetSchema'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { PasswordChecklist } from '@/components/ui/password-checklist'
import { PasswordStrengthMeter } from '@/components/ui/password-strength-meter'
import { cn } from '@/lib/utils'
import { getErrorMessage, isInvalidResetTokenError } from '@/lib/errors'

/**
 * Landing page for the link in the password-reset email
 * ({@code FRONTEND_BASE_URL/reset-password?token=...}). The token is a one-time Redis-backed
 * credential with a 30-minute TTL, so it is read from the URL and posted straight back — never
 * stored, and never put anywhere it could outlive the request.
 */
export function ResetPasswordPage() {
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token')

  const [formError, setFormError] = useState<string | null>(null)
  const [tokenRejected, setTokenRejected] = useState(false)
  const [done, setDone] = useState(false)

  const {
    register,
    handleSubmit,
    watch,
    formState: { errors, isValid, isSubmitting },
  } = useForm<ResetPasswordFormValues>({
    resolver: zodResolver(resetPasswordFormSchema),
    mode: 'onChange',
    defaultValues: { newPassword: '', confirmPassword: '' },
  })

  const passwordValue = watch('newPassword')

  async function onSubmit(values: ResetPasswordFormValues) {
    if (!token) return
    setFormError(null)
    try {
      await resetPassword({ token, newPassword: values.newPassword })
      setDone(true)
    } catch (err) {
      // An expired/used token can't be fixed by editing the form, so it replaces the form
      // entirely with a "request a new link" path instead of showing an inline error.
      if (isInvalidResetTokenError(err)) {
        setTokenRejected(true)
        return
      }
      setFormError(getErrorMessage(err, 'Không thể đặt lại mật khẩu. Vui lòng thử lại.'))
    }
  }

  return (
    <section className="mx-auto flex min-h-[70vh] max-w-md flex-col justify-center px-4 py-16 sm:px-6">
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
      >
        <Card>
          <CardHeader>
            <CardTitle className="text-2xl">Đặt lại mật khẩu</CardTitle>
            {!done && !tokenRejected && token && (
              <p className="text-sm text-muted">Chọn mật khẩu mới cho tài khoản của bạn</p>
            )}
          </CardHeader>
          <CardContent>
            {!token || tokenRejected ? (
              <InvalidLinkState missingToken={!token} />
            ) : done ? (
              <div className="flex flex-col items-center gap-3 py-4 text-center">
                <CheckCircle2 className="size-10 text-primary" />
                <p className="text-sm text-foreground">
                  Đổi mật khẩu thành công! Bạn có thể đăng nhập bằng mật khẩu mới.
                </p>
                <Button asChild variant="gradient" className="mt-2">
                  <Link to="/login">Đăng nhập ngay</Link>
                </Button>
              </div>
            ) : (
              <form onSubmit={handleSubmit(onSubmit)} noValidate className="flex flex-col gap-4">
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="newPassword">Mật khẩu mới</Label>
                  <Input
                    id="newPassword"
                    type="password"
                    autoComplete="new-password"
                    aria-invalid={!!errors.newPassword}
                    className={cn(errors.newPassword && 'border-danger focus-visible:ring-danger')}
                    {...register('newPassword')}
                  />
                  {errors.newPassword && (
                    <p className="text-xs text-danger">{errors.newPassword.message}</p>
                  )}
                  {passwordValue && (
                    <div className="flex flex-col gap-2 pt-1">
                      <PasswordStrengthMeter password={passwordValue} />
                      <PasswordChecklist password={passwordValue} />
                    </div>
                  )}
                </div>

                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="confirmPassword">Nhập lại mật khẩu mới</Label>
                  <Input
                    id="confirmPassword"
                    type="password"
                    autoComplete="new-password"
                    aria-invalid={!!errors.confirmPassword}
                    className={cn(
                      errors.confirmPassword && 'border-danger focus-visible:ring-danger',
                    )}
                    {...register('confirmPassword')}
                  />
                  {errors.confirmPassword && (
                    <p className="text-xs text-danger">{errors.confirmPassword.message}</p>
                  )}
                </div>

                {formError && (
                  <p className="rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 text-sm text-danger">
                    {formError}
                  </p>
                )}

                <Button
                  type="submit"
                  variant="gradient"
                  disabled={!isValid || isSubmitting}
                  className="mt-2"
                >
                  {isSubmitting ? 'Đang đổi mật khẩu…' : 'Đổi mật khẩu'}
                </Button>
              </form>
            )}
          </CardContent>
        </Card>
      </motion.div>
    </section>
  )
}

function InvalidLinkState({ missingToken }: { missingToken: boolean }) {
  return (
    <div className="flex flex-col items-center gap-3 py-4 text-center">
      {missingToken ? (
        <LinkIcon className="size-10 text-warning" />
      ) : (
        <ShieldAlert className="size-10 text-danger" />
      )}
      <p className="text-sm text-foreground">
        {missingToken
          ? 'Link đặt lại mật khẩu không hợp lệ — thiếu mã xác thực.'
          : 'Link này đã hết hạn hoặc đã được dùng rồi.'}
      </p>
      <p className="text-xs text-muted">
        Mỗi link chỉ dùng được một lần và hết hạn sau 30 phút. Hãy yêu cầu một link mới.
      </p>
      <Button asChild variant="gradient" className="mt-2">
        <Link to="/forgot-password">Gửi lại link đặt lại mật khẩu</Link>
      </Button>
      <Link to="/login" className="text-sm text-accent hover:underline">
        Quay lại đăng nhập
      </Link>
    </div>
  )
}
