// Shape returned by notification-service via api-gateway (GET /api/v1/notifications).
export interface Notification {
  notificationId: string
  title: string
  body: string
  read: boolean
  createdAt: string // ISO timestamp
}

export interface NotificationListResponse {
  items: Notification[]
  totalElements: number
  page: number
  size: number
}
