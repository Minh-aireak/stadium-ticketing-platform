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

/**
 * Accumulates the leagues seen across every response, rather than reading them off the page
 * currently on screen.
 *
 * <p>There is no leagues endpoint, so the chip row has to come from the matches themselves — but
 * deriving it from `matches` made the filter destroy its own options: picking a league narrowed
 * the results to that league, and every other chip vanished with the matches it came from. The
 * only way to reach a second league was to notice the search box had been filled in and edit it
 * by hand. Paging had the milder version of the same problem, since a chip only existed while a
 * match from its league happened to be on the current page of eight.
 *
 * <p>Returns the previous array unchanged when nothing is new, so the chip row does not re-render
 * on every fetch.
 */
function rememberLeagues(known: string[], items: Match[]): string[] {
  const merged = new Set(known)
  items.forEach((item) => merged.add(item.competition))
  return merged.size === known.length ? known : Array.from(merged).sort()
}

export function FeaturedMatches() {
  const { toast } = useToast()
  const [queryInput, setQueryInput] = useState('')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [matches, setMatches] = useState<Match[]>([])
  const [leagues, setLeagues] = useState<string[]>([])
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
        setLeagues((known) => rememberLeagues(known, res.items))
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

  // Both listing modes paginate. The search path (q param) forwards page/size to Elasticsearch
  // and reports its totalHits, and since 90b96a2 that total counts the same PUBLISHED matches the
  // page hands back — so it divides into pages exactly as the browse total does.
  const totalPages = Math.max(1, Math.ceil(totalElements / PAGE_SIZE))

  return (
    <section className="mx-auto max-w-6xl px-4 py-16 sm:px-6">
      <div id="leagues" className="mb-14 scroll-mt-24 text-center">
        <h2 className="text-3xl font-bold sm:text-4xl">Giải đấu nổi bật</h2>
        <p className="mt-2 text-muted">Lọc nhanh trận đấu theo giải bạn quan tâm</p>

        {leagues.length > 0 && (
          <div className="mt-6 flex flex-wrap justify-center gap-2">
            {leagues.map((league) => {
              const active = query === league
              return (
                <button
                  key={league}
                  type="button"
                  onClick={() => setQueryInput(active ? '' : league)}
                  className={`rounded-full border px-4 py-1.5 text-sm font-medium transition-colors ${
                    active
                      ? 'border-primary bg-primary text-primary-foreground'
                      : 'border-border bg-surface-2 text-muted hover:text-foreground'
                  }`}
                >
                  {league}
                </button>
              )
            })}
          </div>
        )}
      </div>

      <div id="matches" className="scroll-mt-24">
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

            {totalPages > 1 && (
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
      </div>
    </section>
  )
}
