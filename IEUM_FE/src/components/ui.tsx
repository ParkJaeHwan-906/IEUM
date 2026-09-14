import { useEffect, useState, type ReactNode } from 'react'
import type { OrderState } from '../types/api'
import { orderStateLabel } from '../lib/format'

export function StateBadge({ state }: { state: OrderState }) {
  return <span className={`badge badge--state badge--${state}`}>{orderStateLabel[state]}</span>
}

export function Empty({ icon = '🍃', title, children }: { icon?: string; title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <div className="icon">{icon}</div>
      <div style={{ fontWeight: 700, color: 'var(--ink-700)' }}>{title}</div>
      {children && <div style={{ marginTop: 8 }}>{children}</div>}
    </div>
  )
}

export function Skeleton({ count = 4, height = 220 }: { count?: number; height?: number }) {
  return (
    <>
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="skeleton" style={{ height }} />
      ))}
    </>
  )
}

export function Toast({ message, onDone }: { message: string | null; onDone: () => void }) {
  const [visible, setVisible] = useState(false)
  useEffect(() => {
    if (!message) return
    setVisible(true)
    const t = setTimeout(() => {
      setVisible(false)
      onDone()
    }, 2200)
    return () => clearTimeout(t)
  }, [message, onDone])
  if (!message || !visible) return null
  return <div className="toast">{message}</div>
}

export function useAsync<T>(loader: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [loading, setLoading] = useState(true)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    loader()
      .then((value) => {
        if (alive) setData(value)
      })
      .catch((e) => {
        if (alive) setError(e)
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [...deps, tick])

  return { data, error, loading, reload: () => setTick((t) => t + 1), setData }
}
