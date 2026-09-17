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

// Card mode: after Stripe.js reports the confirmation, ask payment-service to read the outcome
// from Stripe itself and record it. Without this the outcome would only arrive by webhook, which
// on a developer machine means never. Idempotent; the browser's own verdict is not sent.
export async function syncPayment(bookingId: string): Promise<PaymentStatusResponse> {
  const { data } = await api.post<PaymentStatusResponse>(`/payments/${bookingId}/sync`)
  return data
}
