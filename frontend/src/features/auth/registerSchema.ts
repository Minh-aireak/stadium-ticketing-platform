import { z } from 'zod'

import {
  DIGIT_PATTERN,
  LOWERCASE_PATTERN,
  PASSWORD_MIN_LENGTH,
  UPPERCASE_PATTERN,
} from './passwordPolicy'

// The password rules match identity-service's RawPassword.validate exactly, through the shared
// patterns in passwordPolicy.ts: minimum length plus one uppercase, one lowercase and one digit,
// each spelled as the Unicode property RawPassword's Character.isX predicate actually tests.
// Shared with the reset-password form (see passwordResetSchema.ts) — RawPassword enforces the
// same policy on both endpoints, so the two forms must never drift apart.
//
// emailSchema's *format* rule is NOT a mirror and is not meant to be. Email.java accepts anything
// matching ^[^@\s]+@[^@\s]+\.[^@\s]+$; zod's .email() is tighter and rejects a handful of things
// that pattern lets through — "a@b.c" (one-letter TLD), "a@b.1", "user@host_name.de", "a..b@c.de".
// That is the safe direction (the server never sees an address the client waved past) and none
// of them is deliverable mail, so the stricter client check stays.
//
// Its *length* rule is a mirror, for that same reason read the other way. Email.java caps the
// address at 254 characters and zod's .email() carries no length bound at all, so without
// EMAIL_MAX_LENGTH the client would wave past an address the server answers 422 for — the unsafe
// direction, and the one this file exists to prevent.
export const passwordSchema = z
  .string()
  .min(1, 'Vui lòng nhập mật khẩu')
  .min(PASSWORD_MIN_LENGTH, `Mật khẩu phải có ít nhất ${PASSWORD_MIN_LENGTH} ký tự`)
  .regex(UPPERCASE_PATTERN, 'Mật khẩu phải có ít nhất 1 chữ hoa')
  .regex(LOWERCASE_PATTERN, 'Mật khẩu phải có ít nhất 1 chữ thường')
  .regex(DIGIT_PATTERN, 'Mật khẩu phải có ít nhất 1 chữ số')

/**
 * The longest address RFC 5321 permits — a 256-octet path minus the two angle brackets — which is
 * also the cap identity-service's Email enforces and what `accounts.email` (VARCHAR(255)) holds.
 */
export const EMAIL_MAX_LENGTH = 254

export const emailSchema = z
  .string()
  .trim()
  .min(1, 'Vui lòng nhập email')
  .max(EMAIL_MAX_LENGTH, `Email không được quá ${EMAIL_MAX_LENGTH} ký tự`)
  .email('Email không đúng định dạng')

export const registerFormSchema = z
  .object({
    email: emailSchema,
    password: passwordSchema,
    confirmPassword: z.string().min(1, 'Vui lòng nhập lại mật khẩu'),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Mật khẩu nhập lại không khớp',
    path: ['confirmPassword'],
  })

export type RegisterFormValues = z.infer<typeof registerFormSchema>
