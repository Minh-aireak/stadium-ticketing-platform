import { Ticket } from 'lucide-react'

export function Footer() {
  return (
    <footer className="border-t border-border">
      <div className="mx-auto flex max-w-6xl flex-col items-center gap-4 px-4 py-10 text-center sm:px-6">
        <div className="flex items-center gap-2 font-heading text-base font-bold">
          <Ticket className="size-5 text-primary" />
          <span>StadiumGo</span>
        </div>
        <p className="max-w-md text-sm text-muted">
          Đặt vé xem bóng đá nhanh chóng, an toàn — chọn ghế trực quan, thanh
          toán tức thì, vé điện tử có mặt ngay trong tài khoản của bạn.
        </p>
        <p className="text-xs text-muted">
          © {new Date().getFullYear()} StadiumGo. Mọi thứ ở đây chỉ mang tính
          minh hoạ.
        </p>
      </div>
    </footer>
  )
}
