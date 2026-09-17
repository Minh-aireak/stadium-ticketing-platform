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
  /**
   * Card mode, while INITIATED: the PaymentIntent client secret Stripe.js confirms. Absent (or
   * null) for an auto-mode payment and once the payment is terminal -- its presence is what tells
   * the status page to show the card form instead of the spinner.
   */
  clientSecret?: string | null
  /** Card mode, while INITIATED: ISO-8601 instant after which the seats go back on sale. */
  expiresAt?: string | null
}
