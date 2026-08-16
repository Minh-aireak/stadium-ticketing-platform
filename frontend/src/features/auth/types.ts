// Mirrors identity-service's LoginRequest/LoginResponse/RegisterRequest/RegisterResponse DTOs.
export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresInSeconds: number
}

export interface RegisterRequest {
  email: string
  password: string
}

export interface RegisterResponse {
  accountId: string
  message: string
}

export interface ForgotPasswordRequest {
  email: string
}

export interface ResetPasswordRequest {
  token: string
  newPassword: string
}

export type AccountRole = 'USER' | 'ADMIN'

export interface AuthUser {
  id: string
  email: string
  role: AccountRole
}
