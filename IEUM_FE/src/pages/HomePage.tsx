import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { listAllItems, listStores } from '../api/stores'
import ItemCard from '../components/ItemCard'
import StoreCard from '../components/StoreCard'
import { Empty, Skeleton, useAsync } from '../components/ui'
import { messageOf } from '../api/errors'
import type { StoreType } from '../types/api'
import { storeTypeEmoji, storeTypeLabel } from '../lib/format'

const types: StoreType[] = ['FOOD', 'CAFE', 'BAKERY', 'FAST_FOOD', 'FOOD_INGREDIENTS', 'BAR']

export default function HomePage() {
  const [filter, setFilter] = useState<StoreType | 'ALL'>('ALL')
  const { data, loading, error } = useAsync(
    async () => {
      const [stores, items] = await Promise.all([listStores(), listAllItems()])
      return { stores, items }
    },
    [],
  )

  const storeMap = useMemo(() => new Map(data?.stores.map((s) => [s.storeUid, s]) ?? []), [data])

  const onSale = useMemo(() => {
    if (!data) return []
    const now = Date.now()
    return data.items
      .filter((i) => !storeMap.get(i.storeUid)?.shutdown)
      .filter((i) => filter === 'ALL' || storeMap.get(i.storeUid)?.storeType === filter)
      .sort((a, b) => {
        const aOpen = a.remainingQuantity > 0 && new Date(a.lastOrderTime).getTime() > now ? 0 : 1
        const bOpen = b.remainingQuantity > 0 && new Date(b.lastOrderTime).getTime() > now ? 0 : 1
        if (aOpen !== bOpen) return aOpen - bOpen
        return new Date(a.lastOrderTime).getTime() - new Date(b.lastOrderTime).getTime()
      })
  }, [data, filter, storeMap])

  const savedTotal = useMemo(
    () => data?.items.reduce((sum, i) => sum + (i.originalPrice - i.salePrice) * (i.initialQuantity - i.remainingQuantity), 0) ?? 0,
    [data],
  )

  return (
    <>
      <section className="hero">
        <h1>
          오늘 마감 전,
          <br />
          동네 가게의 남은 상품을 예약하세요
        </h1>
        <p>소상공인의 마감 상품을 할인가로 예약하고 직접 방문해 픽업합니다. 버려질 음식이 줄어듭니다.</p>
        <div className="stats">
          <div className="stat">
            <b>{data ? data.stores.filter((s) => !s.shutdown).length : '-'}</b>
            <span>참여 매장</span>
          </div>
          <div className="stat">
            <b>{data ? data.items.filter((i) => i.remainingQuantity > 0).length : '-'}</b>
            <span>판매 중 상품</span>
          </div>
          <div className="stat">
            <b>{data ? `${Math.round(savedTotal / 10000)}만원` : '-'}</b>
            <span>누적 절약 금액</span>
          </div>
        </div>
      </section>

      <section className="section">
        <div className="section__head">
          <h2>지금 예약 가능한 상품</h2>
          <Link to="/stores">매장별 보기</Link>
        </div>
        <div className="chips" style={{ marginBottom: 12 }}>
          <button type="button" className={`chip ${filter === 'ALL' ? 'active' : ''}`} onClick={() => setFilter('ALL')}>
            전체
          </button>
          {types.map((t) => (
            <button key={t} type="button" className={`chip ${filter === t ? 'active' : ''}`} onClick={() => setFilter(t)}>
              {storeTypeEmoji[t]} {storeTypeLabel[t]}
            </button>
          ))}
        </div>
        {error ? <div className="alert alert--error">{messageOf(error)}</div> : null}
        <div className="grid">
          {loading && <Skeleton count={8} />}
          {!loading && onSale.map((item) => <ItemCard key={item.itemUid} item={item} store={storeMap.get(item.storeUid)} />)}
        </div>
        {!loading && !error && onSale.length === 0 && <Empty title="이 카테고리에는 아직 판매 중인 상품이 없어요" />}
      </section>

      <section className="section">
        <div className="section__head">
          <h2>참여 매장</h2>
          <Link to="/stores">전체 보기</Link>
        </div>
        <div className="stack">
          {loading && <Skeleton count={3} height={76} />}
          {data?.stores
            .filter((s) => !s.shutdown)
            .slice(0, 4)
            .map((s) => (
              <StoreCard key={s.storeUid} store={s} itemCount={data.items.filter((i) => i.storeUid === s.storeUid && i.remainingQuantity > 0).length} />
            ))}
        </div>
      </section>
    </>
  )
}
