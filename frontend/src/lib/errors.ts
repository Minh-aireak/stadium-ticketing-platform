import { isAxiosError } from 'axios'

// Backend errors follow RFC 7807 ProblemDetail (see common/GlobalExceptionHandler):
// { type, title, status, detail, timestamp }. `detail` is the human-readable message.
export function getErrorMessage(
  error: unknown,
  fallback = 'Đã có lỗi xảy ra, vui lòng thử lại.',
): string {
  if (isAxiosError(error)) {
    if (error.code === 'ERR_NETWORK') {
      return 'Không thể kết nối tới máy chủ. Vui lòng kiểm tra lại kết nối hoặc thử lại sau.'
    }
    const detail = error.response?.data?.detail
    if (typeof detail === 'string' && detail.length > 0) return detail
  }
  return fallback
}
