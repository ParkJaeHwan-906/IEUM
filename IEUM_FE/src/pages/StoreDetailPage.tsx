import { Link, useParams } from 'react-router-dom'
import { getStore, listStoreItems } from '../api/stores'
import ItemCard from '../components/ItemCard'
import { Empty, Skeleton, useAsync } from '../components/ui'
import { messageOf } from '../api/errors'
import { storeTypeEmoji, storeTypeLabel, time } from '../lib/format'

export default function StoreDetailPage() {
  const { storeUid = '' } = useParams()
  const { data, loading, error } = useAsync(
    async () => {
      const [store, items] = await Promise.all([getStore(storeUid), listStoreItems(storeUid)])
      return { store, items }
    },
    [storeUid],
  )

  if (error) {
    return (
      <Empty icon="🏚️" title={messageOf(error)}>
        <Link to="/stores" className="btn btn--ghost btn--sm">
          매장 목록으로
        </Link>
      </Empty>
    )
  }

  if (loading || !data) return <Skeleton count={1} height={140} />

  const { store, items } = data

  return (
    <>
      <div className="card store-card" style={{ marginBottom: 20 }}>
        <div className="store-card__logo" style={{ width: 64, height: 64, fontSize: 32 }}>
          {store.logoImgUrl ? <img src={store.logoImgUrl} alt="" /> : storeTypeEmoji[store.storeType]}
        </div>
        <div className="store-card__body">
          <div className="store-card__name" style={{ fontSize: 18 }}>
            {store.name}
            {store.shutdown && <span className="badge badge--state">영업 종료</span>}
          </div>
          <div className="store-card__sub">
            {storeTypeLabel[store.storeType]} · 영업 {time(store.openAt)} ~ {time(store.closeAt)}
          </div>
        </div>
      </div>

      <section className="section">
        <div className="section__head">
          <h2>마감 상품 {items.length}개</h2>
        </div>
        {store.shutdown && <div className="alert alert--info" style={{ marginBottom: 12 }}>영업을 종료한 매장입니다. 예약할 수 없습니다.</div>}
        <div className="grid">
          {items.map((item) => (
            <ItemCard key={item.itemUid} item={item} store={store} />
          ))}
        </div>
        {items.length === 0 && <Empty title="등록된 마감 상품이 없어요" />}
      </section>
    </>
  )
}
