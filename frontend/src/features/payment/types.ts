// Mirrors payment-service's PaymentController DTOs.
export type PaymentStatus = 'INITIATED' | 'SUCCEEDED' | 'FAILED' | 'REFUNDED'

export interface InitiatePaymentRequest {
  bookingId: string
  amount: number
  currency: string
}

export interface InitiatePaymentResponse {
  paymentId: string
}

export interface PaymentStatusResponse {
  paymentId: string
  bookingId: string
  status: PaymentStatus
  gatewayTransactionId: string | null
  failureReason: string | null
}
