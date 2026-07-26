import { useEffect, useState } from 'react'

export interface CountdownParts {
  days: number
  hours: number
  minutes: number
  seconds: number
  isPast: boolean
}

function diffToParts(diffMs: number): CountdownParts {
  const isPast = diffMs <= 0
  const abs = Math.max(diffMs, 0)
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
    const id = setInterval(() => {
      setParts(diffToParts(target - Date.now()))
    }, 1000)
    return () => clearInterval(id)
  }, [targetIso])

  return parts
}
