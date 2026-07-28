import { api } from '@/lib/api'
import type { NotificationListResponse } from './types'

export interface ListNotificationsParams {
  page?: number
  size?: number
}

// "My notifications" — always scoped server-side to the JWT-authenticated caller.
export async function listMyNotifications(
  params: ListNotificationsParams = {},
): Promise<NotificationListResponse> {
  const { data } = await api.get<NotificationListResponse>('/notifications', { params })
  return data
}

export async function markNotificationRead(notificationId: string): Promise<void> {
  await api.patch(`/notifications/${notificationId}/read`)
}
