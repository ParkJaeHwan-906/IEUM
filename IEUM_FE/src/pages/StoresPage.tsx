import { useMemo, useState } from 'react'
import { listAllItems, listStores } from '../api/stores'
import StoreCard from '../components/StoreCard'
import { Empty, Skeleton, useAsync } from '../components/ui'
import { messageOf } from '../api/errors'

export default function StoresPage() {
  const [query, setQuery] = useState('')
  const { data, loading, error } = useAsync(
    async () => {
      const [stores, items] = await Promise.all([listStores(), listAllItems()])
      return { stores, items }
    },
    [],
  )

  const filtered = useMemo(() => {
    const q = query.trim()
    const list = data?.stores ?? []
    return q ? list.filter((s) => s.name.includes(q)) : list
  }, [data, query])

  return (
    <section className="section">
      <div className="section__head">
        <h2>참여 매장</h2>
      </div>
      <div className="field" style={{ marginBottom: 14 }}>
        <input placeholder="매장 이름으로 검색" value={query} onChange={(e) => setQuery(e.target.value)} />
      </div>
      {error ? <div className="alert alert--error">{messageOf(error)}</div> : null}
      <div className="stack">
        {loading && <Skeleton count={5} height={76} />}
        {filtered.map((s) => (
          <StoreCard
            key={s.storeUid}
            store={s}
            itemCount={data?.items.filter((i) => i.storeUid === s.storeUid && i.remainingQuantity > 0).length}
          />
        ))}
      </div>
      {!loading && !error && filtered.length === 0 && <Empty title="검색 결과가 없어요" />}
    </section>
  )
}
