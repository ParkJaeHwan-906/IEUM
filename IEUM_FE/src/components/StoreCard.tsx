import { Link } from 'react-router-dom'
import type { StoreResponse } from '../types/api'
import { storeTypeEmoji, storeTypeLabel, time } from '../lib/format'

interface Props {
  store: StoreResponse
  itemCount?: number
}

export default function StoreCard({ store, itemCount }: Props) {
  return (
    <Link to={`/stores/${store.storeUid}`} className="card store-card">
      <div className="store-card__logo">
        {store.logoImgUrl ? <img src={store.logoImgUrl} alt="" /> : storeTypeEmoji[store.storeType]}
      </div>
      <div className="store-card__body">
        <div className="store-card__name">
          {store.name}
          {store.shutdown && <span className="badge badge--state">영업 종료</span>}
        </div>
        <div className="store-card__sub">
          {storeTypeLabel[store.storeType]} · {time(store.openAt)} ~ {time(store.closeAt)}
          {itemCount !== undefined && ` · 판매 중 ${itemCount}개`}
        </div>
      </div>
      <span aria-hidden="true">›</span>
    </Link>
  )
}
