import { createBrowserRouter } from 'react-router-dom'

import App from '@/App'
import { AdminRoute } from '@/features/auth/AdminRoute'
import { ProtectedRoute } from '@/features/auth/ProtectedRoute'
import { HomePage } from '@/pages/HomePage'

// Every page except the landing one is a route-level `lazy`, so its code is fetched on first
// navigation instead of riding in the initial bundle. HomePage stays eager on purpose: it is what
// a browsing visitor lands on, and splitting it would trade one bundle for a bundle plus a second
// round trip on the single most-requested page — the opposite of the point. The route guards are
// eager for the same reason, being a few lines each that decide whether navigation happens at all.
export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'login', lazy: async () => ({ Component: (await import('@/pages/LoginPage')).LoginPage }) },
      { path: 'register', lazy: async () => ({ Component: (await import('@/pages/RegisterPage')).RegisterPage }) },
      {
        path: 'forgot-password',
        lazy: async () => ({ Component: (await import('@/pages/ForgotPasswordPage')).ForgotPasswordPage }),
      },
      // Target of the link in the password-reset email — must stay in sync with the URL
      // notification-service builds from FRONTEND_BASE_URL (see NotificationDispatchService).
      {
        path: 'reset-password',
        lazy: async () => ({ Component: (await import('@/pages/ResetPasswordPage')).ResetPasswordPage }),
      },
      {
        path: 'matches/:matchId',
        lazy: async () => ({ Component: (await import('@/pages/MatchDetailPage')).MatchDetailPage }),
      },
      {
        element: <ProtectedRoute />,
        children: [
          {
            path: 'matches/:matchId/seats',
            lazy: async () => ({ Component: (await import('@/pages/SeatSelectionPage')).SeatSelectionPage }),
          },
          {
            path: 'checkout',
            lazy: async () => ({ Component: (await import('@/pages/CheckoutPage')).CheckoutPage }),
          },
          {
            path: 'checkout/:bookingId/status',
            lazy: async () => ({ Component: (await import('@/pages/PaymentStatusPage')).PaymentStatusPage }),
          },
          {
            path: 'account',
            lazy: async () => ({ Component: (await import('@/pages/AccountPage')).AccountPage }),
          },
          {
            path: 'notifications',
            lazy: async () => ({ Component: (await import('@/pages/NotificationsPage')).NotificationsPage }),
          },
        ],
      },
      {
        element: <AdminRoute />,
        children: [
          { path: 'admin', lazy: async () => ({ Component: (await import('@/pages/AdminPage')).AdminPage }) },
        ],
      },
    ],
  },
])
