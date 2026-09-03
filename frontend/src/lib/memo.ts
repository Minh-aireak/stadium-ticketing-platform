import {
  memo,
  useMemo,
  type ComponentType,
  type MemoExoticComponent,
} from 'react'

type PropsComparator<Props> = (
  previous: Readonly<Props>,
  next: Readonly<Props>,
) => boolean

/**
 * Shared React.memo wrapper that also gives memoized components a useful name
 * in React DevTools.
 */
export function memoComponent<Props extends object>(
  Component: ComponentType<Props>,
  propsAreEqual?: PropsComparator<Props>,
): MemoExoticComponent<ComponentType<Props>> {
  const MemoizedComponent = memo(Component, propsAreEqual)
  // ||, not ??: Function.prototype.name is '' for an unnamed function, never undefined, so
  // the nullish form could not reach the last arm and an anonymous component was labelled
  // 'Memo()' — worse than the name React would have inferred on its own.
  MemoizedComponent.displayName = `Memo(${Component.displayName || Component.name || 'Component'})`
  return MemoizedComponent
}

/** Build a Set only when the source collection identity changes. */
export function useMemoizedSet<Value>(values: readonly Value[]): ReadonlySet<Value> {
  return useMemo(() => new Set(values), [values])
}

/** Build an indexed Map only when the source collection or key changes. */
export function useMemoizedKeyMap<Item, Key extends keyof Item>(
  items: readonly Item[],
  key: Key,
): ReadonlyMap<Item[Key], Item> {
  return useMemo(() => {
    const result = new Map<Item[Key], Item>()
    items.forEach((item) => result.set(item[key], item))
    return result
  }, [items, key])
}
