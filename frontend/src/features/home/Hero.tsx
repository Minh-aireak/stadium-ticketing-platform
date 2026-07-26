import { motion } from 'framer-motion'
import { ArrowRight, Sparkles } from 'lucide-react'

import { Button } from '@/components/ui/button'

const stats = [
  { value: '500+', label: 'trận đấu mỗi mùa' },
  { value: '2M+', label: 'vé đã bán' },
  { value: '50+', label: 'sân vận động' },
]

export function Hero() {
  return (
    <section
      id="top"
      className="relative overflow-hidden bg-stadium-glow px-4 pt-20 pb-24 sm:px-6"
    >
      <motion.div
        aria-hidden
        className="pointer-events-none absolute -top-24 right-[10%] size-72 rounded-full bg-accent/20 blur-3xl"
        animate={{ y: [0, 24, 0] }}
        transition={{ duration: 8, repeat: Infinity, ease: 'easeInOut' }}
      />
      <motion.div
        aria-hidden
        className="pointer-events-none absolute top-32 left-[5%] size-64 rounded-full bg-primary/20 blur-3xl"
        animate={{ y: [0, -20, 0] }}
        transition={{ duration: 7, repeat: Infinity, ease: 'easeInOut' }}
      />

      <div className="relative mx-auto flex max-w-4xl flex-col items-center gap-6 text-center">
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5 }}
          className="inline-flex items-center gap-2 rounded-full border border-border bg-surface-2 px-4 py-1.5 text-sm text-muted"
        >
          <Sparkles className="size-4 text-accent" />
          Mở bán vé mùa giải mới
        </motion.div>

        <motion.h1
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, delay: 0.1 }}
          className="text-4xl font-extrabold sm:text-5xl md:text-6xl"
        >
          Đặt vé sân vận động,
          <br />
          <span className="text-gradient-brand">sống trọn không khí trận đấu</span>
        </motion.h1>

        <motion.p
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, delay: 0.2 }}
          className="max-w-xl text-base text-muted sm:text-lg"
        >
          Chọn ghế trực quan theo sơ đồ khán đài, thanh toán tức thì, nhận vé
          điện tử ngay lập tức — không xếp hàng, không lo hết vé.
        </motion.p>

        <motion.div
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, delay: 0.3 }}
          className="flex flex-col gap-3 sm:flex-row"
        >
          <Button variant="gradient" size="lg">
            Xem trận đấu sắp diễn ra
            <ArrowRight className="size-4" />
          </Button>
          <Button variant="outline" size="lg">
            Tìm hiểu thêm
          </Button>
        </motion.div>

        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.5, delay: 0.4 }}
          className="mt-6 grid grid-cols-3 gap-6 border-t border-border pt-6"
        >
          {stats.map((stat) => (
            <div key={stat.label}>
              <div className="text-2xl font-bold sm:text-3xl">{stat.value}</div>
              <div className="text-xs text-muted sm:text-sm">{stat.label}</div>
            </div>
          ))}
        </motion.div>
      </div>
    </section>
  )
}
