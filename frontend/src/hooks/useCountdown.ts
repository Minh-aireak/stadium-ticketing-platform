import { useEffect, useState } from 'react'

export interface CountdownParts {
  days: number
  hours: number
  minutes: number
  seconds: number
  isPast: boolean
}

function diffToParts(diffMs: number): CountdownParts {
  const isPast = !(diffMs > 0)
  const abs = Math.max(diffMs, 0) || 0
  const seconds = Math.floor(abs / 1000) % 60
  const minutes = Math.floor(abs / (1000 * 60)) % 60
  const hours = Math.floor(abs / (1000 * 60 * 60)) % 24
  const days = Math.floor(abs / (1000 * 60 * 60 * 24))
  return { days, hours, minutes, seconds, isPast }
}

export function useCountdown(targetIso: string): CountdownParts {
  const [parts, setParts] = useState(() =>
    diffToParts(new Date(targetIso).getTime() - Date.now()),
  )

  useEffect(() => {
    const target = new Date(targetIso).getTime()
    const current = () => diffToParts(target - Date.now())

    const initial = current()
    setParts(initial)
    // Nothing left to count down to, so no ticker is started. Without this the interval ran for
    // as long as the tab was open, re-rendering a card whose numbers had stopped changing — and
    // MatchCard mounts one of these per card, so a page of eight was eight re-renders a second,
    // forever. An unparseable date lands here too: isPast is written so NaN counts as past,
    // which stops a bad startTime from spinning a ticker that could never clear itself.
    if (initial.isPast) {
      return
    }

    const id = setInterval(() => {
      const next = current()
      setParts(next)
      if (next.isPast) {
        clearInterval(id)
      }
    }, 1000)
    return () => clearInterval(id)
  }, [targetIso])

  return parts
}
