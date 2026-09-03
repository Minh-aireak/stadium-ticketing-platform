import { fireEvent, render } from '@testing-library/react'
import { useState, type ComponentType } from 'react'
import { describe, expect, it } from 'vitest'

import { memoComponent, useMemoizedKeyMap, useMemoizedSet } from './memo'

describe('memoComponent', () => {
  it('names a memoized function component after the function', () => {
    function SeatBadge() {
      return null
    }
    expect(memoComponent(SeatBadge).displayName).toBe('Memo(SeatBadge)')
  })

  it('prefers an explicit displayName', () => {
    function Inner() {
      return null
    }
    Inner.displayName = 'PasswordChecklist'
    expect(memoComponent(Inner).displayName).toBe('Memo(PasswordChecklist)')
  })

  /**
   * Function.prototype.name is always a string — '' for an unnamed function, never undefined —
   * so `?? 'Component'` can never fire and the label degrades to the empty 'Memo()'.
   */
  it('still produces a readable name for an unnamed component', () => {
    const Anonymous = (0, function () {
      return null
    }) as ComponentType<object>
    expect(Anonymous.name).toBe('')
    expect(memoComponent(Anonymous).displayName).toBe('Memo(Component)')
  })

  it('keeps the comparator it is handed', () => {
    let comparisons = 0
    const Memoized = memoComponent(
      ({ value }: { value: number }) => <span>{value}</span>,
      () => {
        comparisons += 1
        return true
      },
    )

    function Host() {
      const [, setTick] = useState(0)
      return (
        <>
          <button onClick={() => setTick((t) => t + 1)}>tick</button>
          <Memoized value={1} />
        </>
      )
    }

    const { getByText } = render(<Host />)
    fireEvent.click(getByText('tick'))
    expect(comparisons).toBeGreaterThan(0)
  })
})

describe('useMemoizedSet / useMemoizedKeyMap', () => {
  it('rebuilds only when the source array identity changes', () => {
    const seen: ReadonlySet<string>[] = []
    const values = ['A1', 'A2']

    function Host({ tick }: { tick: number }) {
      seen.push(useMemoizedSet(values))
      return <span>{tick}</span>
    }

    const { rerender } = render(<Host tick={0} />)
    rerender(<Host tick={1} />)
    expect(seen).toHaveLength(2)
    expect(seen[0]).toBe(seen[1])
    expect([...seen[0]]).toEqual(['A1', 'A2'])
  })

  it('indexes items by the requested key', () => {
    const items = [{ code: 'A1', tier: 'vip' }, { code: 'A2', tier: 'standard' }]
    let map: ReadonlyMap<string, { code: string; tier: string }> | null = null

    function Host() {
      map = useMemoizedKeyMap(items, 'code')
      return null
    }

    render(<Host />)
    expect(map!.get('A2')?.tier).toBe('standard')
    expect(map!.size).toBe(2)
  })
})
