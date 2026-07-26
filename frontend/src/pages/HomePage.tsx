import { Features } from '@/features/home/Features'
import { FeaturedMatches } from '@/features/home/FeaturedMatches'
import { Hero } from '@/features/home/Hero'

export function HomePage() {
  return (
    <>
      <Hero />
      <Features />
      <FeaturedMatches />
    </>
  )
}
