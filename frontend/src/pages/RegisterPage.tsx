import { useState, type FormEvent } from 'react'
import { motion } from 'framer-motion'
import { CheckCircle2 } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useAuth } from '@/features/auth/AuthContext'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { getErrorMessage } from '@/lib/errors'

export function RegisterPage() {
  const { register } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await register(email, password)
      setMessage('Đăng ký thành công! Vui lòng kiểm tra email để xác minh tài khoản.')
    } catch (err) {
      setError(getErrorMessage(err, 'Không thể đăng ký. Vui lòng thử lại.'))
    } finally {
      setSubmitting(false)
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
                <form onSubmit={handleSubmit} className="flex flex-col gap-4">
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="email">Email</Label>
                    <Input
                      id="email"
                      type="email"
                      autoComplete="email"
                      required
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="password">Mật khẩu</Label>
                    <Input
                      id="password"
                      type="password"
                      autoComplete="new-password"
                      minLength={8}
                      required
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                    />
                  </div>

                  {error && (
                    <p className="rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 text-sm text-danger">
                      {error}
                    </p>
                  )}

                  <Button type="submit" variant="gradient" disabled={submitting} className="mt-2">
                    {submitting ? 'Đang đăng ký…' : 'Đăng ký'}
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
