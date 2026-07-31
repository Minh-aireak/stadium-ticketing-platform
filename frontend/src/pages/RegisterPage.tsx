import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { motion } from 'framer-motion'
import { CheckCircle2 } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router-dom'

import { useAuth } from '@/features/auth/AuthContext'
import { registerFormSchema, type RegisterFormValues } from '@/features/auth/registerSchema'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { PasswordChecklist } from '@/components/ui/password-checklist'
import { PasswordStrengthMeter } from '@/components/ui/password-strength-meter'
import { cn } from '@/lib/utils'
import { getErrorMessage, isDuplicateEmailError } from '@/lib/errors'

export function RegisterPage() {
  const { register: registerAccount } = useAuth()
  const [formError, setFormError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    watch,
    setError,
    formState: { errors, isValid, isSubmitting },
  } = useForm<RegisterFormValues>({
    resolver: zodResolver(registerFormSchema),
    mode: 'onChange',
    defaultValues: { email: '', password: '', confirmPassword: '' },
  })

  const passwordValue = watch('password')

  async function onSubmit(values: RegisterFormValues) {
    setFormError(null)
    try {
      await registerAccount(values.email.trim().toLowerCase(), values.password)
      setMessage('Đăng ký thành công! Vui lòng kiểm tra email để xác minh tài khoản.')
    } catch (err) {
      if (isDuplicateEmailError(err)) {
        setError('email', {
          type: 'server',
          message: 'Email này đã được đăng ký. Vui lòng dùng email khác hoặc đăng nhập.',
        })
        return
      }
      setFormError(getErrorMessage(err, 'Không thể đăng ký. Vui lòng thử lại.'))
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
            <CardTitle className="text-2xl">Đăng ký</CardTitle>
            <p className="text-sm text-muted">Tạo tài khoản để bắt đầu đặt vé</p>
          </CardHeader>
          <CardContent>
            {message ? (
              <div className="flex flex-col items-center gap-3 py-4 text-center">
                <CheckCircle2 className="size-10 text-primary" />
                <p className="text-sm text-foreground">{message}</p>
                <Button asChild variant="gradient" className="mt-2">
                  <Link to="/login">Đăng nhập ngay</Link>
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

                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="password">Mật khẩu</Label>
                    <Input
                      id="password"
                      type="password"
                      autoComplete="new-password"
                      aria-invalid={!!errors.password}
                      className={cn(errors.password && 'border-danger focus-visible:ring-danger')}
                      {...register('password')}
                    />
                    {errors.password && <p className="text-xs text-danger">{errors.password.message}</p>}
                    {passwordValue && (
                      <div className="flex flex-col gap-2 pt-1">
                        <PasswordStrengthMeter password={passwordValue} />
                        <PasswordChecklist password={passwordValue} />
                      </div>
                    )}
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="confirmPassword">Nhập lại mật khẩu</Label>
                    <Input
                      id="confirmPassword"
                      type="password"
                      autoComplete="new-password"
                      aria-invalid={!!errors.confirmPassword}
                      className={cn(errors.confirmPassword && 'border-danger focus-visible:ring-danger')}
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
                    {isSubmitting ? 'Đang đăng ký…' : 'Đăng ký'}
                  </Button>
                </form>

                <p className="mt-6 text-center text-sm text-muted">
                  Đã có tài khoản?{' '}
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
