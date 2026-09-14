import { Link } from 'react-router-dom'
import type { ItemResponse, StoreResponse } from '../types/api'
import { discountRate, remaining, storeTypeEmoji, won } from '../lib/format'

interface Props {
  item: ItemResponse
  store?: StoreResponse
}

export default function ItemCard({ item, store }: Props) {
  const rate = discountRate(item.originalPrice, item.salePrice)
  const soldOut = item.remainingQuantity <= 0
  const closed = new Date(item.lastOrderTime).getTime() <= Date.now()

  return (
    <Link to={`/items/${item.itemUid}`} className="card item-card">
      <div className="item-card__thumb">
        {item.itemImgUrl ? <img src={item.itemImgUrl} alt={item.name} /> : <span>{store ? storeTypeEmoji[store.storeType] : '🛍️'}</span>}
        {rate > 0 && <span className="badge">{rate}% 할인</span>}
        {(soldOut || closed) && <div className="soldout">{soldOut ? '품절' : '마감'}</div>}
      </div>
      <div className="item-card__body">
        {store && <span className="item-card__store">{store.name}</span>}
        <span className="item-card__name">{item.name}</span>
        <div className="price">
          <span className="sale">{won(item.salePrice)}</span>
          {rate > 0 && <span className="original">{won(item.originalPrice)}</span>}
        </div>
        <div className="meta">
          <span className="left">{item.remainingQuantity}개 남음</span>
          <span>{remaining(item.lastOrderTime)}</span>
        </div>
      </div>
    </Link>
  )
}
