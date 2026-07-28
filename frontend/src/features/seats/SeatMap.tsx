import { motion } from 'framer-motion'

import { cn } from '@/lib/utils'
import type { Seat } from './types'

interface SeatMapProps {
  seats: Seat[]
  selected: string[]
  onToggle: (code: string) => void
}

const legend: { status: Seat['status'] | 'selected'; label: string; className: string }[] = [
  { status: 'available', label: 'Còn trống', className: 'border border-border bg-surface-2' },
  { status: 'selected', label: 'Đang chọn', className: 'bg-primary text-primary-foreground' },
  { status: 'held', label: 'Đang giữ', className: 'bg-warning/30 text-warning' },
  { status: 'sold', label: 'Đã bán', className: 'bg-danger/20 text-danger' },
]

export function SeatMap({ seats, selected, onToggle }: SeatMapProps) {
  // Row count/letters come from the backend layout (see SeatMapLayout), not a fixed A-F —
  // derive them from the actual seats instead of hardcoding, so showtimes with more/fewer
  // seats than the old 6-row mock still render every row.
  const rows = Array.from(new Set(seats.map((s) => s.row))).sort()
  const byRow = rows.map((row) => ({
    row,
    seats: seats.filter((s) => s.row === row).sort((a, b) => a.number - b.number),
  }))

  return (
    <div className="flex flex-col items-center gap-6">
      <div className="w-full max-w-md rounded-full bg-gradient-to-r from-primary/30 via-accent/30 to-primary/30 py-2 text-center text-xs font-semibold tracking-widest text-muted uppercase">
        Mặt sân
      </div>

      <div className="flex flex-col gap-2">
        {byRow.map(({ row, seats: rowSeats }) => (
          <div key={row} className="flex items-center gap-2">
            <span className="w-4 text-xs font-semibold text-muted">{row}</span>
            <div className="flex gap-1.5">
              {rowSeats.map((seat) => {
                const isSelected = selected.includes(seat.code)
                const disabled = seat.status !== 'available' && !isSelected
                return (
                  <motion.button
                    key={seat.code}
                    type="button"
                    disabled={disabled}
                    whileHover={!disabled ? { scale: 1.15 } : undefined}
                    whileTap={!disabled ? { scale: 0.95 } : undefined}
                    onClick={() => onToggle(seat.code)}
                    title={`${seat.code} · ${seat.tier} · ${seat.price.toLocaleString('vi-VN')}đ`}
                    className={cn(
                      'flex size-6 items-center justify-center rounded-md text-[10px] font-medium transition-colors sm:size-7',
                      seat.status === 'available' && !isSelected && 'border border-border bg-surface-2 hover:border-accent',
                      seat.status === 'held' && !isSelected && 'cursor-not-allowed bg-warning/30 text-warning',
                      seat.status === 'sold' && 'cursor-not-allowed bg-danger/20 text-danger',
                      isSelected && 'bg-primary text-primary-foreground shadow-glow-primary',
                    )}
                  >
                    {seat.number}
                  </motion.button>
                )
              })}
            </div>
          </div>
        ))}
      </div>

      <div className="flex flex-wrap justify-center gap-4 text-xs text-muted">
        {legend.map((item) => (
          <div key={item.status} className="flex items-center gap-1.5">
            <span className={cn('size-3 rounded', item.className)} />
            {item.label}
          </div>
        ))}
      </div>
    </div>
  )
}
