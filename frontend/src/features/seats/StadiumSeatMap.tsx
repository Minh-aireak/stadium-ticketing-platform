import { motion, useReducedMotion } from 'framer-motion'
import { ArrowLeft, X } from 'lucide-react'
import { memo, useCallback, useMemo, useState } from 'react'

import { Button } from '@/components/ui/button'
import { useMemoizedSet } from '@/lib/memo'
import { cn } from '@/lib/utils'
import type { StadiumDefinition, StadiumZone, StandId } from './stadiumLayout'
import type { Seat } from './types'

interface StadiumSeatMapProps {
  stadium: StadiumDefinition
  zones: StadiumZone[]
  selected: string[]
  onToggle: (code: string) => void
  onClearSelection: () => void
}

interface Point {
  x: number
  y: number
}

const CENTER = 500
const ZONE_EDGE_PADDING = 3
const SEAT_WIDTH = 20
const SEAT_HEIGHT = 18
const MAP_ASPECT_RATIO = 1000 / 720
const OVERVIEW_SCALE = 0.75

function polarPoint(radius: number, angle: number): Point {
  const radians = angle * Math.PI / 180
  return {
    x: CENTER + radius * Math.sin(radians),
    y: CENTER - radius * Math.cos(radians),
  }
}

function donutSegment(innerRadius: number, outerRadius: number, startAngle: number, endAngle: number) {
  const outerStart = polarPoint(outerRadius, startAngle)
  const outerEnd = polarPoint(outerRadius, endAngle)
  const innerEnd = polarPoint(innerRadius, endAngle)
  const innerStart = polarPoint(innerRadius, startAngle)
  return [
    `M ${outerStart.x} ${outerStart.y}`,
    `A ${outerRadius} ${outerRadius} 0 0 1 ${outerEnd.x} ${outerEnd.y}`,
    `L ${innerEnd.x} ${innerEnd.y}`,
    `A ${innerRadius} ${innerRadius} 0 0 0 ${innerStart.x} ${innerStart.y}`,
    'Z',
  ].join(' ')
}

function arcLine(radius: number, startAngle: number, endAngle: number) {
  const start = polarPoint(radius, startAngle)
  const end = polarPoint(radius, endAngle)
  return `M ${start.x} ${start.y} A ${radius} ${radius} 0 0 1 ${end.x} ${end.y}`
}

function rotationForZone(zone: StadiumZone) {
  return 180 - (zone.startAngle + zone.endAngle) / 2
}

function cameraTransformForZone(zone: StadiumZone, stadium: StadiumDefinition) {
  const midpoint = (zone.startAngle + zone.endAngle) / 2
  const focus = polarPoint(stadium.focusRadius, midpoint)
  const rotation = rotationForZone(zone)
  return [
    'translate(50%, 50%)',
    `rotate(${rotation}deg)`,
    `scale(${stadium.zoomScale})`,
    `translate(${-focus.x / 10}%, ${-focus.y / 10}%)`,
    'translateZ(0px)',
  ].join(' ')
}

function seatRowsWithPositions(zone: StadiumZone) {
  const start = zone.startAngle + ZONE_EDGE_PADDING
  const end = zone.endAngle - ZONE_EDGE_PADDING

  return zone.rows.map((row) => {
    const radius = row.radius
    const labelPoint = polarPoint(radius, end + 4.5)
    const denominator = Math.max(row.seats.length - 1, 1)

    return {
      ...row,
      radius,
      guidePath: arcLine(radius, start, end),
      labelPoint,
      seats: row.seats.map((slot, slotIndex) => {
        const ratio = row.seats.length === 1 ? 0.5 : slotIndex / denominator
        const angle = start + ratio * (end - start)
        return { ...slot, angle, ...polarPoint(radius, angle) }
      }),
    }
  })
}

function tierStroke(tier: Seat['tier']) {
  if (tier === 'vip') return 'stroke-accent-2'
  if (tier === 'premium') return 'stroke-accent'
  return 'stroke-border'
}

function Pitch({ width }: { width: number }) {
  const scale = width / 105
  const height = 68 * scale
  const x = CENTER - width / 2
  const y = CENTER - height / 2
  const lineInset = 10
  const penaltyDepth = 16.5 * scale
  const penaltyWidth = 40.3 * scale
  const sixYardDepth = 5.5 * scale
  const sixYardWidth = 18.32 * scale
  const goalHalfWidth = (7.32 * scale) / 2
  const goalDepth = 13

  return (
    <g aria-label="Sân bóng">
      <rect x={x} y={y} width={width} height={height} rx="12" className="fill-[#123d25] stroke-primary/70" strokeWidth="3" />
      <rect x={x + lineInset} y={y + lineInset} width={width - lineInset * 2} height={height - lineInset * 2} rx="6" fill="none" className="stroke-primary/45" strokeWidth="2" />
      <line x1={CENTER} y1={y + lineInset} x2={CENTER} y2={y + height - lineInset} className="stroke-primary/45" strokeWidth="2" />
      <circle cx={CENTER} cy={CENTER} r={9.15 * scale} fill="none" className="stroke-primary/45" strokeWidth="2" />
      <circle cx={CENTER} cy={CENTER} r="3.5" className="fill-primary/75" />
      <rect x={x} y={CENTER - penaltyWidth / 2} width={penaltyDepth} height={penaltyWidth} fill="none" className="stroke-primary/45" strokeWidth="2" />
      <rect x={x + width - penaltyDepth} y={CENTER - penaltyWidth / 2} width={penaltyDepth} height={penaltyWidth} fill="none" className="stroke-primary/45" strokeWidth="2" />
      <rect x={x} y={CENTER - sixYardWidth / 2} width={sixYardDepth} height={sixYardWidth} fill="none" className="stroke-primary/45" strokeWidth="2" />
      <rect x={x + width - sixYardDepth} y={CENTER - sixYardWidth / 2} width={sixYardDepth} height={sixYardWidth} fill="none" className="stroke-primary/45" strokeWidth="2" />
      <path d={`M ${x} ${CENTER - goalHalfWidth} L ${x - goalDepth} ${CENTER - goalHalfWidth} L ${x - goalDepth} ${CENTER + goalHalfWidth} L ${x} ${CENTER + goalHalfWidth}`} fill="none" className="stroke-accent" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" />
      <path d={`M ${x + width} ${CENTER - goalHalfWidth} L ${x + width + goalDepth} ${CENTER - goalHalfWidth} L ${x + width + goalDepth} ${CENTER + goalHalfWidth} L ${x + width} ${CENTER + goalHalfWidth}`} fill="none" className="stroke-accent" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" />
    </g>
  )
}

function UprightCaption({
  zone,
  radius,
  angleOffset = 0,
  children,
}: {
  zone: StadiumZone
  radius: number
  angleOffset?: number
  children: string
}) {
  const point = polarPoint(radius, (zone.startAngle + zone.endAngle) / 2 + angleOffset)
  const counterRotation = -rotationForZone(zone)
  return (
    <text
      x={point.x}
      y={point.y}
      transform={`rotate(${counterRotation} ${point.x} ${point.y})`}
      textAnchor="middle"
      dominantBaseline="middle"
      className="pointer-events-none fill-foreground/80 text-[9px] font-bold tracking-[0.1em]"
    >
      {children}
    </text>
  )
}

function StadiumSeatMapComponent({ stadium, zones, selected, onToggle, onClearSelection }: StadiumSeatMapProps) {
  const reduceMotion = useReducedMotion()
  const [activeId, setActiveId] = useState<StandId | null>(null)
  const activeZone = zones.find((zone) => zone.id === activeId) ?? null
  const positionedZones = useMemo(
    () => zones.map((zone) => ({ zone, rows: seatRowsWithPositions(zone) })),
    [zones],
  )
  const selectedSet = useMemoizedSet(selected)
  const levelDetails = useMemo(() => stadium.levels.map((level, index) => {
    const rows = stadium.rows.filter((row) => row.level === level.number)
    return {
      level,
      firstRow: rows[0]?.label,
      lastRow: rows[rows.length - 1]?.label,
      fill: index % 2 === 0 ? 'url(#tier-one-gradient)' : 'url(#tier-two-gradient)',
    }
  }), [stadium])
  const remainingSeatsByZone = useMemo(() => new Map(zones.map((zone) => [
    zone.id,
    zone.seats.reduce(
      (count, seat) => count + (seat.status === 'available' && !selectedSet.has(seat.code) ? 1 : 0),
      0,
    ),
  ])), [selectedSet, zones])
  const selectedMarkers = useMemo(
    () => positionedZones.flatMap(({ rows }) => rows.flatMap((row) => row.seats.flatMap((slot) => (
      slot.seat && selectedSet.has(slot.seat.code)
        ? [{ code: slot.seat.code, x: slot.x, y: slot.y }]
        : []
    )))),
    [positionedZones, selectedSet],
  )

  const remainingSeats = useCallback(
    (zone: StadiumZone) => remainingSeatsByZone.get(zone.id) ?? 0,
    [remainingSeatsByZone],
  )

  const cameraTransform = useMemo(
    () => activeZone
      ? cameraTransformForZone(activeZone, stadium)
      : `translate(50%, 50%) rotate(0deg) scale(${OVERVIEW_SCALE}) translate(-50%, -50%) translateZ(0px)`,
    [activeZone, stadium],
  )

  const openZone = useCallback((id: StandId) => {
    setActiveId((current) => current ?? id)
  }, [])

  const leaveZone = useCallback(() => {
    setActiveId(null)
  }, [])

  const activeMeta = activeZone
    ? levelDetails.map(({ level, firstRow, lastRow }) => (
        `${level.name}: hàng ${firstRow}–${lastRow}`
      )).join(' · ')
    : 'Chọn một khán đài để xem ghế'

  return (
    <div className="w-full overflow-hidden rounded-2xl border border-border bg-surface shadow-card">
      <div className="grid min-h-[70px] grid-cols-[40px_minmax(0,1fr)_auto] items-center gap-2 border-b border-border bg-background/65 px-3 py-2 sm:grid-cols-[minmax(130px,1fr)_auto_minmax(130px,1fr)] sm:gap-3 sm:px-4">
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={leaveZone}
          className={cn(
            'w-10 justify-self-start bg-surface-2/80 px-0 backdrop-blur sm:w-auto sm:px-3',
            !activeZone && 'invisible pointer-events-none',
          )}
          tabIndex={activeZone ? 0 : -1}
        >
          <ArrowLeft className="size-4" />
          <span className="hidden sm:inline">Quay lại</span>
        </Button>

        <div className="min-w-0 text-center" aria-live="polite">
          <strong className="block truncate text-sm sm:text-[15px]">
            {activeZone?.name ?? 'Toàn cảnh sân vận động'}
          </strong>
          <span className="mt-0.5 block truncate text-[10px] text-muted sm:text-[11px]">
            {activeZone ? `${stadium.totalSeats / 4} ghế · ${activeMeta}` : activeMeta}
          </span>
        </div>

        <div className="flex items-center justify-self-end gap-2 text-xs text-muted">
          <span className="hidden sm:inline">Đã chọn</span>
          <span className="grid size-8 place-items-center rounded-full border border-primary/35 bg-primary/10 font-extrabold text-primary">
            {selected.length}
          </span>
          <button
            type="button"
            disabled={selected.length === 0}
            onClick={onClearSelection}
            className="grid size-8 place-items-center rounded-lg border border-border bg-surface-2/80 text-muted transition-colors hover:border-accent hover:text-foreground disabled:cursor-default disabled:opacity-35"
            title={selected.length === 0 ? 'Chưa có ghế được chọn' : 'Bỏ chọn tất cả'}
            aria-label="Bỏ chọn tất cả"
          >
            <X className="size-4" />
          </button>
        </div>
      </div>

      <div
        className="relative w-full overflow-hidden bg-[radial-gradient(circle_at_center,rgba(34,211,238,0.08),transparent_58%)]"
        style={{ aspectRatio: MAP_ASPECT_RATIO, contain: 'layout paint' }}
      >
        <div className="absolute top-1/2 left-0 aspect-square w-full -translate-y-1/2">
          <motion.div
            initial={false}
            animate={{ transform: cameraTransform }}
            transition={reduceMotion
              ? { duration: 0 }
              : { duration: 0.46, ease: [0.22, 1, 0.36, 1] }}
            className="absolute inset-0"
            style={{
              transformOrigin: '0 0',
              willChange: 'transform',
              backfaceVisibility: 'hidden',
            }}
          >
          <svg
            viewBox="0 0 1000 1000"
            className="size-full"
            aria-label={`Sơ đồ ${stadium.name} có bốn khán đài và ${stadium.levels.length} tầng`}
          >
            <defs>
              <linearGradient id="tier-one-gradient" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stopColor="#202943" />
                <stop offset="1" stopColor="#151b2c" />
              </linearGradient>
              <linearGradient id="tier-two-gradient" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stopColor="#182136" />
                <stop offset="1" stopColor="#101522" />
              </linearGradient>
            </defs>

            {stadium.design === 'multi-tier' ? (
              <rect x="26" y="42" width="948" height="916" rx="120" className="fill-surface-2/30 stroke-border/40" strokeWidth="2" />
            ) : stadium.design === 'compact' ? (
              <ellipse cx={CENTER} cy={CENTER} rx={stadium.outlineRadius} ry={stadium.outlineRadius - 24} className="fill-surface-2/30 stroke-border/40" strokeWidth="2" />
            ) : (
              <circle cx={CENTER} cy={CENTER} r={stadium.outlineRadius} className="fill-surface-2/30 stroke-border/40" strokeWidth="2" />
            )}
            {zones.map((zone) => {
              const isActive = activeId === zone.id
              const isDimmed = activeId !== null && !isActive
              const labelRadius = stadium.levels.length > 1
                ? (stadium.levels[0].outerRadius + stadium.levels[1].innerRadius) / 2
                : (stadium.levels[0].innerRadius + stadium.levels[0].outerRadius) / 2
              const labelPoint = polarPoint(labelRadius, (zone.startAngle + zone.endAngle) / 2)
              return (
                <motion.g
                  key={zone.id}
                  animate={{ opacity: isDimmed ? 0.12 : 1 }}
                  transition={reduceMotion
                    ? { duration: 0 }
                    : isDimmed
                      ? { duration: 0.12, ease: 'easeOut' }
                      : activeId
                        ? { duration: 0.16, ease: 'easeOut' }
                        : { duration: 0.18, delay: 0.26, ease: 'easeOut' }}
                >
                  {levelDetails.map(({ level, fill }) => (
                    <motion.path
                      key={`level-${level.number}`}
                      d={donutSegment(level.innerRadius, level.outerRadius, zone.startAngle, zone.endAngle)}
                      fill={fill}
                      className={cn(
                        'cursor-pointer stroke-border outline-none transition-colors',
                        isActive && 'stroke-accent',
                      )}
                      strokeWidth={isActive ? 4 : 2}
                      whileHover={!activeId ? { stroke: '#22d3ee' } : undefined}
                      onClick={() => openZone(zone.id)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault()
                          openZone(zone.id)
                        }
                      }}
                      role="button"
                      tabIndex={activeId ? -1 : 0}
                      aria-label={`Mở ${zone.name}, ${level.name}, còn ${remainingSeats(zone)} ghế`}
                    />
                  ))}
                  <text
                    x={labelPoint.x}
                    y={labelPoint.y}
                    textAnchor="middle"
                    dominantBaseline="middle"
                    aria-hidden={Boolean(activeId)}
                    className="pointer-events-none fill-foreground text-[18px] font-bold tracking-wide"
                    style={{
                      opacity: activeId ? 0 : 1,
                      transition: reduceMotion
                        ? 'none'
                        : activeId
                          ? 'opacity 70ms linear'
                          : 'opacity 160ms ease-out 300ms',
                    }}
                  >
                    <tspan x={labelPoint.x} dy="-0.35em">{zone.name.replace('Khán đài ', '').toUpperCase()}</tspan>
                    <tspan x={labelPoint.x} dy="1.5em" className="fill-muted text-[10px] font-semibold">
                      {stadium.levels.length} tầng · {stadium.totalSeats / 4} vị trí · còn {remainingSeats(zone)}
                    </tspan>
                  </text>
                </motion.g>
              )
            })}

            <motion.g
              animate={{ opacity: activeId ? 0.3 : 1 }}
              transition={reduceMotion
                ? { duration: 0 }
                : activeId
                  ? { duration: 0.12, ease: 'easeOut' }
                  : { duration: 0.18, delay: 0.24, ease: 'easeOut' }}
            >
              <Pitch width={stadium.pitchWidth} />
            </motion.g>

            <g
              aria-hidden="true"
              className="pointer-events-none"
              style={{
                opacity: activeId ? 0 : 1,
                transition: reduceMotion
                  ? 'none'
                  : activeId
                    ? 'opacity 80ms linear'
                    : 'opacity 160ms ease-out 360ms',
              }}
            >
              {selectedMarkers.map((marker) => (
                <circle
                  key={`selected-marker-${marker.code}`}
                  cx={marker.x}
                  cy={marker.y}
                  r="5"
                  className="fill-primary stroke-background"
                  strokeWidth="2"
                />
              ))}
            </g>

            {positionedZones.map(({ zone: seatZone, rows: positionedRows }) => {
              const isVisible = activeId === seatZone.id
              return (
                <g
                  key={`seats-${seatZone.id}`}
                  aria-hidden={!isVisible}
                  style={{
                    opacity: isVisible ? 1 : 0,
                    pointerEvents: isVisible ? 'auto' : 'none',
                    transition: reduceMotion
                      ? 'none'
                      : isVisible
                        ? 'opacity 160ms ease-out 210ms'
                        : 'opacity 60ms linear',
                  }}
                >
                  {positionedRows.map((positionedRow) => (
                    <g key={positionedRow.id}>
                      <path d={positionedRow.guidePath} fill="none" className="stroke-border/45" strokeWidth="1" strokeDasharray="3 6" />
                      <text
                        x={positionedRow.labelPoint.x}
                        y={positionedRow.labelPoint.y}
                        transform={`rotate(${-rotationForZone(seatZone)} ${positionedRow.labelPoint.x} ${positionedRow.labelPoint.y})`}
                        textAnchor="middle"
                        dominantBaseline="middle"
                        className="pointer-events-none fill-muted text-[9px] font-bold"
                      >
                        {positionedRow.label}
                      </text>
                      {positionedRow.seats.map(({ seat, displayNumber, angle, x, y }) => {
                        const key = `${positionedRow.id}-${displayNumber}`
                        const isPlaceholder = seat === null
                        const isSelected = seat ? selectedSet.has(seat.code) : false
                        const disabled = !seat || (seat.status !== 'available' && !isSelected)
                        const counterRotation = -angle - rotationForZone(seatZone)

                        return (
                          <g key={key} transform={`rotate(${angle} ${x} ${y})`}>
                            <motion.g
                              role="button"
                              tabIndex={isVisible && !disabled ? 0 : -1}
                              aria-disabled={disabled}
                              aria-label={isPlaceholder
                                ? `Hàng ${positionedRow.label}, vị trí ${displayNumber}, chưa khả dụng`
                                : `${positionedRow.label}${displayNumber}, mã ghế ${seat.code}, ${seat.tier}, ${seat.status}`}
                              className={disabled ? 'cursor-not-allowed outline-none' : 'cursor-pointer outline-none'}
                              style={{ transformBox: 'fill-box', transformOrigin: 'center' }}
                              animate={{ scale: isSelected ? 1.05 : 1 }}
                              whileHover={!disabled ? { scale: 1.1 } : undefined}
                              whileTap={!disabled ? { scale: 0.96 } : undefined}
                              onClick={() => seat && isVisible && !disabled && onToggle(seat.code)}
                              onKeyDown={(event) => {
                                if (seat && isVisible && !disabled && (event.key === 'Enter' || event.key === ' ')) {
                                  event.preventDefault()
                                  onToggle(seat.code)
                                }
                              }}
                            >
                              <title>
                                {isPlaceholder
                                  ? `${positionedRow.label}${displayNumber} · Chưa khả dụng`
                                  : `${positionedRow.label}${displayNumber} · Mã ${seat.code} · ${seat.price.toLocaleString('vi-VN')}đ`}
                              </title>
                              <rect
                                x={x - SEAT_WIDTH / 2}
                                y={y - SEAT_HEIGHT / 2}
                                width={SEAT_WIDTH}
                                height={SEAT_HEIGHT}
                                rx="4"
                                strokeWidth={isSelected ? 2 : 1.6}
                                strokeDasharray={isPlaceholder ? '2 2' : undefined}
                                className={cn(
                                  isPlaceholder && 'fill-surface-2/30 stroke-border/60 opacity-45',
                                  seat && tierStroke(seat.tier),
                                  seat?.status === 'available' && !isSelected && 'fill-surface-2',
                                  seat?.status === 'held' && !isSelected && 'fill-warning/50 stroke-warning',
                                  seat?.status === 'sold' && 'fill-danger/40 stroke-danger',
                                  isSelected && 'fill-primary stroke-primary',
                                )}
                                style={isSelected ? { filter: 'drop-shadow(0 0 5px rgb(163 230 53 / 0.8))' } : undefined}
                              />
                              <text
                                x={x}
                                y={y + 0.5}
                                transform={`rotate(${counterRotation} ${x} ${y})`}
                                textAnchor="middle"
                                dominantBaseline="middle"
                                className={cn(
                                  'pointer-events-none text-[6.4px] font-bold',
                                  isPlaceholder ? 'fill-muted/55' : 'fill-foreground',
                                  isSelected && 'fill-primary-foreground',
                                )}
                              >
                                {displayNumber}
                              </text>
                            </motion.g>
                          </g>
                        )
                      })}
                    </g>
                  ))}

                  {levelDetails.map(({ level, firstRow, lastRow }) => (
                    <UprightCaption key={level.number} zone={seatZone} radius={level.captionRadius}>
                      {`${level.name.toUpperCase()} · HÀNG ${firstRow}–${lastRow}`}
                    </UprightCaption>
                  ))}
                  {stadium.levels.slice(0, -1).map((level, index) => {
                    const nextLevel = stadium.levels[index + 1]
                    return (
                      <UprightCaption
                        key={`aisle-${level.number}`}
                        zone={seatZone}
                        radius={(level.outerRadius + nextLevel.innerRadius) / 2}
                        angleOffset={-stadium.zoneHalfSpan * 0.55}
                      >
                        LỐI ĐI
                      </UprightCaption>
                    )
                  })}
                </g>
              )
            })}
            </svg>
          </motion.div>
        </div>
      </div>
    </div>
  )
}

export const StadiumSeatMap = memo(StadiumSeatMapComponent)
