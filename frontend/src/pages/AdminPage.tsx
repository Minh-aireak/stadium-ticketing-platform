import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { motion } from 'framer-motion'
import { Loader2, PlusCircle, ShieldCheck } from 'lucide-react'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  addShowtime,
  cancelMatch,
  completeMatch,
  createMatch,
  listActiveMatches,
  listStadiums,
  publishMatch,
} from '@/features/admin/adminMatchesApi'
import type { StadiumSummary } from '@/features/admin/types'
import { useAuth } from '@/features/auth/AuthContext'
import type { Match } from '@/features/matches/types'
import { useToast } from '@/hooks/useToast'
import { getErrorMessage } from '@/lib/errors'
import { formatCurrency, formatKickoff } from '@/lib/format'

const PAGE_SIZE = 10

interface DraftShowtime {
  startTime: string
  stadiumId: string
  stadiumName: string
  totalSeats: number
  basePrice: number
  currency: string
}

interface Draft {
  matchId: string
  homeTeam: string
  awayTeam: string
  competition: string
  showtimes: DraftShowtime[]
}

export function AdminPage() {
  const { user } = useAuth()
  const { toast } = useToast()

  const [matches, setMatches] = useState<Match[]>([])
  const [totalElements, setTotalElements] = useState(0)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [creating, setCreating] = useState(false)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [homeTeam, setHomeTeam] = useState('')
  const [awayTeam, setAwayTeam] = useState('')
  const [competition, setCompetition] = useState('')

  const [addingShowtime, setAddingShowtime] = useState(false)
  const [stadiums, setStadiums] = useState<StadiumSummary[]>([])
  const [startTime, setStartTime] = useState('')
  const [stadiumId, setStadiumId] = useState('')
  const [basePrice, setBasePrice] = useState('')
  const [currency, setCurrency] = useState('VND')

  const [publishing, setPublishing] = useState(false)

  const [completingId, setCompletingId] = useState<string | null>(null)
  const [cancellingId, setCancellingId] = useState<string | null>(null)
  const [cancelReason, setCancelReason] = useState('')
  const [cancelSubmitting, setCancelSubmitting] = useState(false)

  const loadMatches = useCallback(() => {
    setLoading(true)
    setError(null)
    listActiveMatches({ page, size: PAGE_SIZE })
      .then((res) => {
        setMatches(res.items)
        setTotalElements(res.totalElements)
      })
      .catch((err: unknown) => {
        const message = getErrorMessage(err, 'Không thể tải danh sách trận đấu.')
        setError(message)
      })
      .finally(() => setLoading(false))
  }, [page])

  useEffect(() => {
    loadMatches()
  }, [loadMatches])

  useEffect(() => {
    listStadiums()
      .then((items) => {
        setStadiums(items)
        setStadiumId((current) => current || items[0]?.id || '')
      })
      .catch((err: unknown) => {
        toast({ title: 'Không thể tải danh sách sân', description: getErrorMessage(err), variant: 'error' })
      })
  }, [toast])

  const totalPages = Math.max(1, Math.ceil(totalElements / PAGE_SIZE))

  async function handleCreateMatch(e: FormEvent) {
    e.preventDefault()
    setCreating(true)
    try {
      const { matchId } = await createMatch({ homeTeam, awayTeam, competition })
      setDraft({ matchId, homeTeam, awayTeam, competition, showtimes: [] })
      setHomeTeam('')
      setAwayTeam('')
      setCompetition('')
      toast({ title: 'Đã tạo trận đấu (nháp)', description: 'Hãy thêm suất bán vé rồi xuất bản.' })
    } catch (err) {
      toast({ title: 'Không thể tạo trận đấu', description: getErrorMessage(err), variant: 'error' })
    } finally {
      setCreating(false)
    }
  }

  async function handleAddShowtime(e: FormEvent) {
    e.preventDefault()
    if (!draft) return
    setAddingShowtime(true)
    try {
      const isoStartTime = new Date(startTime).toISOString()
      const price = Number(basePrice)
      const stadium = stadiums.find((item) => item.id === stadiumId)
      if (!stadium) throw new Error('Vui lòng chọn sân vận động')
      await addShowtime(draft.matchId, {
        startTime: isoStartTime,
        stadiumId,
        basePrice: price,
        currency,
      })
      setDraft({
        ...draft,
        showtimes: [
          ...draft.showtimes,
          {
            startTime: isoStartTime,
            stadiumId,
            stadiumName: stadium.name,
            totalSeats: stadium.totalSeats,
            basePrice: price,
            currency,
          },
        ],
      })
      setStartTime('')
      setBasePrice('')
      toast({ title: 'Đã thêm suất bán vé' })
    } catch (err) {
      toast({ title: 'Không thể thêm suất bán vé', description: getErrorMessage(err), variant: 'error' })
    } finally {
      setAddingShowtime(false)
    }
  }

  async function handlePublish() {
    if (!draft) return
    setPublishing(true)
    try {
      await publishMatch(draft.matchId)
      toast({ title: 'Đã xuất bản trận đấu', variant: 'success' })
      setDraft(null)
      setPage(0)
      loadMatches()
    } catch (err) {
      toast({ title: 'Không thể xuất bản trận đấu', description: getErrorMessage(err), variant: 'error' })
    } finally {
      setPublishing(false)
    }
  }

  async function handleComplete(matchId: string) {
    setCompletingId(matchId)
    try {
      await completeMatch(matchId)
      toast({ title: 'Đã hoàn tất trận đấu', variant: 'success' })
      loadMatches()
    } catch (err) {
      toast({ title: 'Không thể hoàn tất trận đấu', description: getErrorMessage(err), variant: 'error' })
    } finally {
      setCompletingId(null)
    }
  }

  async function handleCancelConfirm(matchId: string) {
    if (!cancelReason.trim()) return
    setCancelSubmitting(true)
    try {
      await cancelMatch(matchId, cancelReason.trim())
      toast({ title: 'Đã huỷ trận đấu', variant: 'success' })
      setCancellingId(null)
      setCancelReason('')
      loadMatches()
    } catch (err) {
      toast({ title: 'Không thể huỷ trận đấu', description: getErrorMessage(err), variant: 'error' })
    } finally {
      setCancelSubmitting(false)
    }
  }

  return (
    <section className="mx-auto max-w-4xl px-4 py-12 sm:px-6">
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="flex flex-col gap-8"
      >
        <Card>
          <CardContent className="flex items-center gap-3 p-6">
            <div className="flex size-12 items-center justify-center rounded-full bg-surface-2 text-accent">
              <ShieldCheck className="size-6" />
            </div>
            <div>
              <div className="font-medium">Bảng quản trị</div>
              <div className="text-xs text-muted">Đăng nhập với quyền quản trị · {user?.email}</div>
            </div>
          </CardContent>
        </Card>

        <div>
          <h2 className="mb-4 text-xl font-bold">Tạo trận đấu mới</h2>
          <Card>
            <CardContent className="flex flex-col gap-6 p-6">
              {!draft ? (
                <form onSubmit={handleCreateMatch} className="flex flex-col gap-4">
                  <div className="grid gap-4 sm:grid-cols-2">
                    <div className="flex flex-col gap-1.5">
                      <Label htmlFor="homeTeam">Đội nhà</Label>
                      <Input id="homeTeam" required value={homeTeam} onChange={(e) => setHomeTeam(e.target.value)} />
                    </div>
                    <div className="flex flex-col gap-1.5">
                      <Label htmlFor="awayTeam">Đội khách</Label>
                      <Input id="awayTeam" required value={awayTeam} onChange={(e) => setAwayTeam(e.target.value)} />
                    </div>
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="competition">Giải đấu</Label>
                    <Input id="competition" required value={competition} onChange={(e) => setCompetition(e.target.value)} />
                  </div>
                  <Button type="submit" variant="gradient" disabled={creating} className="self-start">
                    {creating ? 'Đang tạo…' : 'Tạo trận đấu (nháp)'}
                  </Button>
                </form>
              ) : (
                <div className="flex flex-col gap-6">
                  <div className="rounded-lg border border-border bg-surface-2 px-4 py-3">
                    <div className="font-medium">
                      {draft.homeTeam} vs {draft.awayTeam}
                    </div>
                    <div className="text-sm text-muted">{draft.competition} · Trạng thái: Nháp</div>
                  </div>

                  {draft.showtimes.length > 0 && (
                    <div className="flex flex-col gap-2">
                      {draft.showtimes.map((s, i) => (
                        <div key={i} className="flex items-center justify-between rounded-lg border border-border px-4 py-2 text-sm">
                          <span>{s.stadiumName} · {formatKickoff(s.startTime)}</span>
                          <span className="text-muted">{s.totalSeats} ghế · {formatCurrency(s.basePrice)}</span>
                        </div>
                      ))}
                    </div>
                  )}

                  <form onSubmit={handleAddShowtime} className="flex flex-col gap-4">
                    <div className="text-sm font-medium">Thêm suất bán vé</div>
                    <div className="grid gap-4 sm:grid-cols-2">
                      <div className="flex flex-col gap-1.5">
                        <Label htmlFor="startTime">Thời gian bắt đầu</Label>
                        <Input
                          id="startTime"
                          type="datetime-local"
                          required
                          value={startTime}
                          onChange={(e) => setStartTime(e.target.value)}
                        />
                      </div>
                      <div className="flex flex-col gap-1.5">
                        <Label htmlFor="stadiumId">Sân vận động</Label>
                        <select
                          id="stadiumId"
                          required
                          value={stadiumId}
                          onChange={(event) => setStadiumId(event.target.value)}
                          className="h-10 w-full rounded-md border border-border bg-surface-2 px-3 text-sm text-foreground outline-none focus:border-accent"
                        >
                          {stadiums.map((stadium) => (
                            <option key={stadium.id} value={stadium.id}>
                              {stadium.name} · {stadium.levels} tầng · {stadium.totalSeats} ghế
                            </option>
                          ))}
                        </select>
                        {stadiums.find((stadium) => stadium.id === stadiumId) ? (
                          <span className="text-xs text-muted">
                            Sức chứa và sơ đồ ghế được cố định theo sân đã chọn.
                          </span>
                        ) : null}
                      </div>
                      <div className="flex flex-col gap-1.5">
                        <Label htmlFor="basePrice">Giá vé cơ bản</Label>
                        <Input
                          id="basePrice"
                          type="number"
                          min={0}
                          step="0.01"
                          required
                          value={basePrice}
                          onChange={(e) => setBasePrice(e.target.value)}
                        />
                      </div>
                      <div className="flex flex-col gap-1.5">
                        <Label htmlFor="currency">Đơn vị tiền tệ</Label>
                        <Input
                          id="currency"
                          required
                          maxLength={3}
                          value={currency}
                          onChange={(e) => setCurrency(e.target.value.toUpperCase())}
                        />
                      </div>
                    </div>
                    <div className="flex flex-wrap gap-3">
                      <Button type="submit" variant="outline" disabled={addingShowtime}>
                        <PlusCircle className="size-4" />
                        {addingShowtime ? 'Đang thêm…' : 'Thêm suất bán vé'}
                      </Button>
                      <Button
                        type="button"
                        variant="gradient"
                        disabled={draft.showtimes.length === 0 || publishing}
                        onClick={handlePublish}
                      >
                        {publishing ? 'Đang xuất bản…' : 'Xuất bản trận đấu'}
                      </Button>
                      <Button type="button" variant="ghost" onClick={() => setDraft(null)} disabled={publishing}>
                        Huỷ bỏ nháp
                      </Button>
                    </div>
                  </form>
                </div>
              )}
            </CardContent>
          </Card>
        </div>

        <div>
          <h2 className="mb-4 text-xl font-bold">Trận đấu đang mở bán</h2>

          {loading && (
            <div className="flex justify-center py-12">
              <Loader2 className="size-8 animate-spin text-accent" />
            </div>
          )}

          {!loading && error && (
            <p className="rounded-lg border border-danger/40 bg-danger/10 px-4 py-3 text-center text-sm text-danger">
              {error}
            </p>
          )}

          {!loading && !error && matches.length === 0 && (
            <p className="text-center text-muted">Chưa có trận đấu nào đang mở bán.</p>
          )}

          {!loading && !error && matches.length > 0 && (
            <>
              <div className="flex flex-col gap-4">
                {matches.map((match) => (
                  <Card key={match.matchId}>
                    <CardContent className="flex flex-col gap-4 p-6">
                      <div className="flex items-center justify-between gap-4">
                        <div>
                          <div className="font-medium">
                            {match.homeTeam} vs {match.awayTeam}
                          </div>
                          <div className="text-sm text-muted">
                            {match.competition} · {match.showtimes.length} suất bán vé
                          </div>
                        </div>
                        <Badge>{match.status}</Badge>
                      </div>

                      <div className="flex flex-wrap gap-3">
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={completingId === match.matchId}
                          onClick={() => handleComplete(match.matchId)}
                        >
                          {completingId === match.matchId ? 'Đang xử lý…' : 'Hoàn tất'}
                        </Button>
                        {cancellingId === match.matchId ? (
                          <div className="flex flex-1 flex-wrap items-center gap-2">
                            <Input
                              placeholder="Lý do huỷ trận"
                              value={cancelReason}
                              onChange={(e) => setCancelReason(e.target.value)}
                              className="max-w-xs"
                            />
                            <Button
                              size="sm"
                              variant="gradient"
                              disabled={!cancelReason.trim() || cancelSubmitting}
                              onClick={() => handleCancelConfirm(match.matchId)}
                            >
                              {cancelSubmitting ? 'Đang huỷ…' : 'Xác nhận huỷ'}
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={cancelSubmitting}
                              onClick={() => {
                                setCancellingId(null)
                                setCancelReason('')
                              }}
                            >
                              Thôi
                            </Button>
                          </div>
                        ) : (
                          <Button variant="outline" size="sm" onClick={() => setCancellingId(match.matchId)}>
                            Huỷ trận
                          </Button>
                        )}
                      </div>
                    </CardContent>
                  </Card>
                ))}
              </div>

              {totalPages > 1 && (
                <div className="mt-6 flex items-center justify-center gap-4">
                  <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
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
      </motion.div>
    </section>
  )
}
