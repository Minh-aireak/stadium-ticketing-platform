// TODO: notification-service has no REST controller yet (only routed at the gateway) —
// wire this up to a real GET /api/v1/notifications once one exists.
export interface MockNotification {
  id: string
  title: string
  body: string
  createdAt: string
  read: boolean
}

export const mockNotifications: MockNotification[] = [
  {
    id: 'n1',
    title: 'Đặt vé thành công',
    body: 'Vé xem Song Han FC vs Thanh Long United của bạn đã được xác nhận.',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 2).toISOString(),
    read: false,
  },
  {
    id: 'n2',
    title: 'Sắp mở bán vé mới',
    body: 'AFC Champions League: Hoang Kim SC vs Rong Vang mở bán vé vào 8h sáng mai.',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 26).toISOString(),
    read: true,
  },
]
