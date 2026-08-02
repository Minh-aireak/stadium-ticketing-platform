import { createBrowserRouter } from 'react-router-dom'

import App from '@/App'
import { AdminRoute } from '@/features/auth/AdminRoute'
import { ProtectedRoute } from '@/features/auth/ProtectedRoute'
import { AccountPage } from '@/pages/AccountPage'
import { AdminPage } from '@/pages/AdminPage'
import { CheckoutPage } from '@/pages/CheckoutPage'
import { HomePage } from '@/pages/HomePage'
import { LoginPage } from '@/pages/LoginPage'
import { MatchDetailPage } from '@/pages/MatchDetailPage'
import { NotificationsPage } from '@/pages/NotificationsPage'
import { PaymentStatusPage } from '@/pages/PaymentStatusPage'
import { RegisterPage } from '@/pages/RegisterPage'
import { SeatSelectionPage } from '@/pages/SeatSelectionPage'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'register', element: <RegisterPage /> },
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
