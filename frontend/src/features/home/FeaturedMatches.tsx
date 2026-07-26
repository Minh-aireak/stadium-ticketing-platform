import { motion } from 'framer-motion'

import { mockFeaturedMatches } from '@/features/matches/mockMatches'
import { MatchCard } from '@/features/matches/MatchCard'

export function FeaturedMatches() {
  return (
    <section id="matches" className="mx-auto max-w-6xl px-4 py-16 sm:px-6">
      <div className="mb-10 flex flex-col gap-2 text-center">
        <h2 className="text-3xl font-bold sm:text-4xl">Trận đấu nổi bật</h2>
        <p className="text-muted">
          Những trận cầu đáng chú ý nhất đang mở bán vé
        </p>
      </div>

      <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
        {mockFeaturedMatches.map((match, index) => (
          <motion.div
            key={match.id}
            initial={{ opacity: 0, y: 24 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true, margin: '-60px' }}
            transition={{ duration: 0.4, delay: index * 0.08 }}
          >
            <MatchCard match={match} />
          </motion.div>
        ))}
      </div>
    </section>
  )
}
