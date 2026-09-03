import { useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { Bell, Menu, ShieldCheck, Ticket, X } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { useAuth } from '@/features/auth/AuthContext'
import { useToast } from '@/hooks/useToast'
import { getErrorMessage } from '@/lib/errors'

const navLinks = [
  { label: 'Trận đấu', to: '/#matches' },
  { label: 'Giải đấu', to: '/#leagues' },
]

export function Navbar() {
  const [open, setOpen] = useState(false)
  const { user, status, logout } = useAuth()
  const navigate = useNavigate()
  const { toast } = useToast()

  async function handleLogout() {
    setOpen(false)
    try {
      await logout()
    } catch (err: unknown) {
      // See AccountPage#handleLogout: the local session is cleared regardless, but a failed
      // request means the server was never told, and the customer needs to know to retry.
      toast({
        title: 'Đăng xuất chưa hoàn tất',
        description: getErrorMessage(err, 'Không thể báo cho máy chủ. Vui lòng thử đăng xuất lại.'),
        variant: 'error',
      })
    } finally {
      navigate('/')
    }
  }

  return (
    <header className="sticky top-0 z-50 glass">
      <nav className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3 sm:px-6">
        <Link to="/" className="flex items-center gap-2 font-heading text-lg font-bold">
          <Ticket className="size-6 text-primary" />
          <span className="text-gradient-brand">StadiumGo</span>
        </Link>

        <div className="hidden items-center gap-8 md:flex">
          {navLinks.map((link) => (
            <Link
              key={link.to}
              to={link.to}
              className="text-sm font-medium text-muted transition-colors hover:text-foreground"
            >
              {link.label}
            </Link>
          ))}
          {status === 'authenticated' && (
            <Link
              to="/account"
              className="text-sm font-medium text-muted transition-colors hover:text-foreground"
            >
              Vé của tôi
            </Link>
          )}
          {status === 'authenticated' && user?.role === 'ADMIN' && (
            <Link
              to="/admin"
              className="flex items-center gap-1.5 text-sm font-medium text-muted transition-colors hover:text-foreground"
            >
              <ShieldCheck className="size-4" />
              Quản trị
            </Link>
          )}
        </div>

        <div className="hidden items-center gap-3 md:flex">
          {status === 'authenticated' ? (
            <>
              <Link
                to="/notifications"
                aria-label="Thông báo"
                className="text-muted transition-colors hover:text-foreground"
              >
                <Bell className="size-5" />
              </Link>
              <span className="text-sm text-muted">{user?.email}</span>
              <Button variant="outline" size="sm" onClick={handleLogout}>
                Đăng xuất
              </Button>
            </>
          ) : (
            <>
              <Button asChild variant="ghost" size="sm">
                <Link to="/login">Đăng nhập</Link>
              </Button>
              <Button asChild variant="gradient" size="sm">
                <Link to="/register">Đăng ký</Link>
              </Button>
            </>
          )}
        </div>

        {/*
          One control for both directions, so its name has to move with it — it read "Mở menu"
          while it was the button that closes the menu. aria-expanded is what actually announces
          the state change; the label says what pressing it will do next.
        */}
        <button
          type="button"
          aria-label={open ? 'Đóng menu' : 'Mở menu'}
          aria-expanded={open}
          className="text-foreground md:hidden"
          onClick={() => setOpen((v) => !v)}
        >
          {open ? <X className="size-6" /> : <Menu className="size-6" />}
        </button>
      </nav>

      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden border-t border-border md:hidden"
          >
            <div className="flex flex-col gap-4 px-4 py-4">
              {navLinks.map((link) => (
                <Link
                  key={link.to}
                  to={link.to}
                  onClick={() => setOpen(false)}
                  className="text-sm font-medium text-muted hover:text-foreground"
                >
                  {link.label}
                </Link>
              ))}

              {status === 'authenticated' ? (
                <>
                  <Link to="/account" onClick={() => setOpen(false)} className="text-sm font-medium text-muted hover:text-foreground">
                    Vé của tôi
                  </Link>
                  <Link to="/notifications" onClick={() => setOpen(false)} className="text-sm font-medium text-muted hover:text-foreground">
                    Thông báo
                  </Link>
                  {user?.role === 'ADMIN' && (
                    <Link
                      to="/admin"
                      onClick={() => setOpen(false)}
                      className="flex items-center gap-1.5 text-sm font-medium text-muted hover:text-foreground"
                    >
                      <ShieldCheck className="size-4" />
                      Quản trị
                    </Link>
                  )}
                  <Button variant="outline" size="sm" onClick={handleLogout}>
                    Đăng xuất ({user?.email})
                  </Button>
                </>
              ) : (
                <div className="flex gap-2 pt-2">
                  <Button asChild variant="ghost" size="sm" className="flex-1">
                    <Link to="/login" onClick={() => setOpen(false)}>Đăng nhập</Link>
                  </Button>
                  <Button asChild variant="gradient" size="sm" className="flex-1">
                    <Link to="/register" onClick={() => setOpen(false)}>Đăng ký</Link>
                  </Button>
                </div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </header>
  )
}
