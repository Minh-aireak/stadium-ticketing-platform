import { createBrowserRouter } from 'react-router-dom'

import App from '@/App'
import { AdminRoute } from '@/features/auth/AdminRoute'
import { ProtectedRoute } from '@/features/auth/ProtectedRoute'
import { AccountPage } from '@/pages/AccountPage'
import { AdminPage } from '@/pages/AdminPage'
import { CheckoutPage } from '@/pages/CheckoutPage'
import { ForgotPasswordPage } from '@/pages/ForgotPasswordPage'
import { HomePage } from '@/pages/HomePage'
import { LoginPage } from '@/pages/LoginPage'
import { MatchDetailPage } from '@/pages/MatchDetailPage'
import { NotificationsPage } from '@/pages/NotificationsPage'
import { PaymentStatusPage } from '@/pages/PaymentStatusPage'
import { RegisterPage } from '@/pages/RegisterPage'
import { ResetPasswordPage } from '@/pages/ResetPasswordPage'
import { SeatSelectionPage } from '@/pages/SeatSelectionPage'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'register', element: <RegisterPage /> },
      { path: 'forgot-password', element: <ForgotPasswordPage /> },
      // Target of the link in the password-reset email — must stay in sync with the URL
      // notification-service builds from FRONTEND_BASE_URL (see NotificationDispatchService).
      { path: 'reset-password', element: <ResetPasswordPage /> },
      { path: 'matches/:matchId', element: <MatchDetailPage /> },
      {
        element: <ProtectedRoute />,
        children: [
          { path: 'matches/:matchId/seats', element: <SeatSelectionPage /> },
          { path: 'checkout', element: <CheckoutPage /> },
          { path: 'checkout/:bookingId/status', element: <PaymentStatusPage /> },
          { path: 'account', element: <AccountPage /> },
          { path: 'notifications', element: <NotificationsPage /> },
        ],
      },
      {
        element: <AdminRoute />,
        children: [{ path: 'admin', element: <AdminPage /> }],
      },
    ],
  },
])
