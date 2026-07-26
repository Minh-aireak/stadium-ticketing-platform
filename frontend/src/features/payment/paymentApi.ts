import { api } from '@/lib/api'
import type { InitiatePaymentRequest, InitiatePaymentResponse, PaymentStatusResponse } from './types'

export async function initiatePayment(
  payload: InitiatePaymentRequest,
): Promise<InitiatePaymentResponse> {
  const { data } = await api.post<InitiatePaymentResponse>('/payments', payload)
  return data
}

export async function getPaymentStatus(bookingId: string): Promise<PaymentStatusResponse> {
  const { data } = await api.get<PaymentStatusResponse>(`/payments/${bookingId}`)
  return data
}
