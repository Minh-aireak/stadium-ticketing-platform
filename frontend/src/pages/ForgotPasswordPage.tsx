import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { motion } from 'framer-motion'
import { MailCheck } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router-dom'

import { forgotPassword } from '@/features/auth/authApi'
import {
  forgotPasswordFormSchema,
  type ForgotPasswordFormValues,
} from '@/features/auth/passwordResetSchema'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'
import { getErrorMessage } from '@/lib/errors'

export function ForgotPasswordPage() {
  const [formError, setFormError] = useState<string | null>(null)
  const [submittedEmail, setSubmittedEmail] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isValid, isSubmitting },
  } = useForm<ForgotPasswordFormValues>({
    resolver: zodResolver(forgotPasswordFormSchema),
    mode: 'onChange',
    defaultValues: { email: '' },
  })

  async function onSubmit(values: ForgotPasswordFormValues) {
    setFormError(null)
    const email = values.email.trim().toLowerCase()
    try {
      await forgotPassword({ email })
      setSubmittedEmail(email)
    } catch (err) {
      setFormError(getErrorMessage(err, 'Không thể gửi yêu cầu. Vui lòng thử lại.'))
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
            <CardTitle className="text-2xl">Quên mật khẩu</CardTitle>
            <p className="text-sm text-muted">
              Nhập email của bạn, chúng tôi sẽ gửi link đặt lại mật khẩu
            </p>
          </CardHeader>
          <CardContent>
            {submittedEmail ? (
              /*
               * Worded so it says nothing about whether the address has an account — the backend
               * answers 204 either way on purpose (see RequestPasswordResetService), and a
               * message like "đã gửi tới email của bạn" would leak exactly what it protects.
               */
              <div className="flex flex-col items-center gap-3 py-4 text-center">
                <MailCheck className="size-10 text-primary" />
                <p className="text-sm text-foreground">
                  Nếu <strong>{submittedEmail}</strong> đang gắn với một tài khoản, chúng tôi đã gửi
                  link đặt lại mật khẩu tới hòm thư đó.
                </p>
                <p className="text-xs text-muted">
                  Link có hiệu lực trong 30 phút và chỉ dùng được một lần. Nhớ kiểm tra cả thư mục
                  spam.
                </p>
                <Button asChild variant="gradient" className="mt-2">
                  <Link to="/login">Quay lại đăng nhập</Link>
                </Button>
              </div>
            ) : (
              <>
                <form onSubmit={handleSubmit(onSubmit)} noValidate className="flex flex-col gap-4">
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="email">Email</Label>
                    <Input
                      id="email"
                      type="email"
                      autoComplete="email"
                      aria-invalid={!!errors.email}
                      className={cn(errors.email && 'border-danger focus-visible:ring-danger')}
                      {...register('email')}
                    />
                    {errors.email && <p className="text-xs text-danger">{errors.email.message}</p>}
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
                    {isSubmitting ? 'Đang gửi…' : 'Gửi link đặt lại mật khẩu'}
                  </Button>
                </form>

                <p className="mt-6 text-center text-sm text-muted">
                  Nhớ ra mật khẩu rồi?{' '}
                  <Link to="/login" className="text-accent hover:underline">
                    Đăng nhập
                  </Link>
                </p>
              </>
            )}
          </CardContent>
        </Card>
      </motion.div>
    </section>
  )
}
