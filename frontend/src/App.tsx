import { useEffect } from 'react'
import { Outlet, useLocation } from 'react-router-dom'

import { Navbar } from '@/components/layout/Navbar'

function App() {
  const location = useLocation()

  // Plain <a href="#id"> only rescrolls when already on the page that has the target section —
  // it can't get you there from another route. Nav links now always route through "/", so once
  // that navigation lands we still need to scroll to the hash ourselves.
  useEffect(() => {
    if (!location.hash) return
    const id = location.hash.slice(1)
    const raf = requestAnimationFrame(() => {
      document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    })
    return () => cancelAnimationFrame(raf)
  }, [location.pathname, location.hash])

  return (
    <div className="flex min-h-screen flex-col">
      <Navbar />
      <main className="flex-1">
        <Outlet />
      </main>
    </div>
  )
}

export default App
