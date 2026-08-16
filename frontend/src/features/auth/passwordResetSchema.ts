import { z } from 'zod'

import { emailSchema, passwordSchema } from './registerSchema'

export const forgotPasswordFormSchema = z.object({
  email: emailSchema,
})

export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordFormSchema>

// Same password rules as registration — identity-service validates both through RawPassword.
export const resetPasswordFormSchema = z
  .object({
    newPassword: passwordSchema,
    confirmPassword: z.string().min(1, 'Vui lòng nhập lại mật khẩu'),
  })
  .refine((data) => data.newPassword === data.confirmPassword, {
    message: 'Mật khẩu nhập lại không khớp',
    path: ['confirmPassword'],
  })

export type ResetPasswordFormValues = z.infer<typeof resetPasswordFormSchema>
