import { motion } from 'framer-motion'
import { LayoutGrid, QrCode, ShieldCheck } from 'lucide-react'

const features = [
  {
    icon: LayoutGrid,
    title: 'Chọn ghế trực quan',
    description:
      'Sơ đồ khán đài thời gian thực — thấy ngay ghế trống, đang giữ hay đã bán trước khi đặt.',
  },
  {
    icon: ShieldCheck,
    title: 'Thanh toán an toàn',
    description:
      'Giao dịch được xác thực và chống trùng lặp, không lo bị trừ tiền hai lần cho một vé.',
  },
  {
    icon: QrCode,
    title: 'Vé điện tử tức thì',
    description:
      'Nhận vé kèm mã QR ngay trong tài khoản, quét vào cửa mà không cần in giấy.',
  },
]

export function Features() {
  return (
    <section className="border-y border-border bg-surface/50 px-4 py-16 sm:px-6">
      <div className="mx-auto grid max-w-6xl gap-6 sm:grid-cols-3">
        {features.map((feature, index) => (
          <motion.div
            key={feature.title}
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true, margin: '-60px' }}
            transition={{ duration: 0.4, delay: index * 0.1 }}
            className="flex flex-col items-center gap-3 rounded-2xl p-6 text-center"
          >
            <div className="flex size-12 items-center justify-center rounded-xl bg-surface-2 text-accent shadow-glow-accent">
              <feature.icon className="size-6" />
            </div>
            <h3 className="text-lg font-semibold">{feature.title}</h3>
            <p className="text-sm text-muted">{feature.description}</p>
          </motion.div>
        ))}
      </div>
    </section>
  )
}
