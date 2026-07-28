import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { Loader2, Search } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { listMatches } from '@/features/matches/matchesApi'
import { MatchCard } from '@/features/matches/MatchCard'
import type { Match } from '@/features/matches/types'
import { useToast } from '@/hooks/useToast'
import { getErrorMessage } from '@/lib/errors'

const PAGE_SIZE = 8
const SEARCH_DEBOUNCE_MS = 350

export function FeaturedMatches() {
  const { toast } = useToast()
  const [queryInput, setQueryInput] = useState('')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [matches, setMatches] = useState<Match[]>([])
  const [totalElements, setTotalElements] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Debounce the search box so every keystroke doesn't fire a request.
  useEffect(() => {
    const id = setTimeout(() => {
      setPage(0)
      setQuery(queryInput.trim())
    }, SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(id)
  }, [queryInput])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    listMatches({ q: query || undefined, page, size: PAGE_SIZE })
      .then((res) => {
        if (cancelled) return
        setMatches(res.items)
        setTotalElements(res.totalElements)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const message = getErrorMessage(err, 'Không thể tải danh sách trận đấu.')
        setError(message)
        toast({ title: 'Không thể tải trận đấu', description: message, variant: 'error' })
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [query, page, toast])

  // The search path (q param) delegates to Elasticsearch and returns an unpaginated
  // result set — pagination only applies to the plain "browse all published" listing.
  const totalPages = Math.max(1, Math.ceil(totalElements / PAGE_SIZE))

  return (
    <section id="matches" className="mx-auto max-w-6xl px-4 py-16 sm:px-6">
      <div className="mb-10 flex flex-col gap-2 text-center">
        <h2 className="text-3xl font-bold sm:text-4xl">Trận đấu nổi bật</h2>
        <p className="text-muted">
          Những trận cầu đáng chú ý nhất đang mở bán vé
        </p>
      </div>

      <div className="mx-auto mb-8 max-w-md">
        <div className="relative">
          <Search className="absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted" />
          <Input
            value={queryInput}
            onChange={(e) => setQueryInput(e.target.value)}
            placeholder="Tìm theo đội bóng, giải đấu…"
            className="pl-9"
            aria-label="Tìm kiếm trận đấu"
          />
        </div>
      </div>

      {loading && (
        <div className="flex justify-center py-16">
          <Loader2 className="size-8 animate-spin text-accent" />
        </div>
      )}

      {!loading && error && (
        <p className="mx-auto max-w-md rounded-lg border border-danger/40 bg-danger/10 px-4 py-3 text-center text-sm text-danger">
          {error}
        </p>
      )}

      {!loading && !error && matches.length === 0 && (
        <p className="text-center text-muted">Không tìm thấy trận đấu nào.</p>
      )}

      {!loading && !error && matches.length > 0 && (
        <>
          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
            {matches.map((match, index) => (
              <motion.div
                key={match.matchId}
                initial={{ opacity: 0, y: 24 }}
                whileInView={{ opacity: 1, y: 0 }}
                viewport={{ once: true, margin: '-60px' }}
                transition={{ duration: 0.4, delay: index * 0.08 }}
              >
                <MatchCard match={match} />
              </motion.div>
            ))}
          </div>

          {!query && totalPages > 1 && (
            <div className="mt-10 flex items-center justify-center gap-4">
              <Button
                variant="outline"
                size="sm"
                disabled={page === 0}
                onClick={() => setPage((p) => p - 1)}
              >
                Trước
              </Button>
              <span className="text-sm text-muted">
                Trang {page + 1}/{totalPages}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={page + 1 >= totalPages}
                onClick={() => setPage((p) => p + 1)}
              >
                Sau
              </Button>
            </div>
          )}
        </>
      )}
    </section>
  )
}
