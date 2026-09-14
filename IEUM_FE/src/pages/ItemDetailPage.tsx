import { useCallback, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { getItem, getStore } from '../api/stores'
import { createOrder, newIdempotencyKey } from '../api/orders'
import { messageOf } from '../api/errors'
import { useAuth } from '../auth/AuthContext'
import { Empty, Skeleton, Toast, useAsync } from '../components/ui'
import { dateTime, discountRate, remaining, storeTypeEmoji, won } from '../lib/format'

const MAX_QTY = 10

export default function ItemDetailPage() {
  const { itemUid = '' } = useParams()
  const navigate = useNavigate()
  const { user, isConsumer } = useAuth()
  const [qty, setQty] = useState(1)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const [idempotencyKey, setIdempotencyKey] = useState(() => newIdempotencyKey())

  const { data, loading, error: loadError, setData } = useAsync(
    async () => {
      const item = await getItem(itemUid)
      const store = await getStore(item.storeUid)
      return { item, store }
    },
    [itemUid],
  )

  const clearToast = useCallback(() => setToast(null), [])

  if (loadError) {
    return (
      <Empty icon="🔍" title={messageOf(loadError)}>
        <Link to="/" className="btn btn--ghost btn--sm">
          홈으로
        </Link>
      </Empty>
    )
  }
  if (loading || !data) return <Skeleton count={1} height={320} />

  const { item, store } = data
  const rate = discountRate(item.originalPrice, item.salePrice)
  const soldOut = item.remainingQuantity <= 0
  const closed = new Date(item.lastOrderTime).getTime() <= Date.now()
  const canReserve = !soldOut && !closed && !store.shutdown
  const maxQty = Math.max(1, Math.min(MAX_QTY, item.remainingQuantity))
  const stockRatio = item.initialQuantity > 0 ? Math.round((item.remainingQuantity / item.initialQuantity) * 100) : 0

  async function reserve() {
    if (!user) {
      navigate('/login', { state: { from: `/items/${itemUid}` } })
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      await createOrder({ itemUid: item.itemUid, quantity: qty }, idempotencyKey)
      setData({ ...data!, item: { ...item, remainingQuantity: item.remainingQuantity - qty } })
      setIdempotencyKey(newIdempotencyKey())
      setToast('예약이 접수되었습니다. 점주 승인을 기다려 주세요.')
      setTimeout(() => navigate('/orders'), 900)
    } catch (e) {
      setError(messageOf(e))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <div className="detail">
        <div className="detail__thumb">
          {item.itemImgUrl ? <img src={item.itemImgUrl} alt={item.name} /> : storeTypeEmoji[store.storeType]}
        </div>
        <div>
          <Link to={`/stores/${store.storeUid}`} className="item-card__store" style={{ fontSize: 14 }}>
            {store.name} ›
          </Link>
          <h1>{item.name}</h1>
          <div className="price">
            {rate > 0 && <span className="rate">{rate}%</span>}
            <span className="sale">{won(item.salePrice)}</span>
            {rate > 0 && <span className="original">{won(item.originalPrice)}</span>}
          </div>

          <dl className="kv">
            <div>
              <dt>남은 수량</dt>
              <dd>
                {item.remainingQuantity} / {item.initialQuantity}개
                <div className="stock-bar">
                  <span style={{ width: `${stockRatio}%` }} />
                </div>
              </dd>
            </div>
            <div>
              <dt>예약 마감</dt>
              <dd>
                {dateTime(item.lastOrderTime)}
                <div style={{ fontSize: 12, color: 'var(--orange-600)', fontWeight: 600 }}>{remaining(item.lastOrderTime)}</div>
              </dd>
            </div>
            <div>
              <dt>픽업 장소</dt>
              <dd>{store.name}</dd>
            </div>
            <div>
              <dt>픽업 가능 시간</dt>
              <dd>
                {store.openAt.slice(0, 5)} ~ {store.closeAt.slice(0, 5)}
              </dd>
            </div>
          </dl>

          <div className="reserve-box" style={{ marginTop: 16 }}>
            {user && !isConsumer ? (
              <div className="alert alert--info">점주 계정으로는 예약할 수 없습니다. 소비자 계정으로 로그인해 주세요.</div>
            ) : (
              <>
                <div className="qty">
                  <span style={{ fontWeight: 600 }}>수량</span>
                  <div className="qty__control">
                    <button type="button" onClick={() => setQty((q) => Math.max(1, q - 1))} disabled={!canReserve || qty <= 1}>
                      −
                    </button>
                    <span>{qty}</span>
                    <button type="button" onClick={() => setQty((q) => Math.min(maxQty, q + 1))} disabled={!canReserve || qty >= maxQty}>
                      +
                    </button>
                  </div>
                </div>
                <div className="total">
                  <span style={{ color: 'var(--ink-500)' }}>총 결제 예정 금액</span>
                  <b>{won(item.salePrice * qty)}</b>
                </div>
                {error && <div className="alert alert--error">{error}</div>}
                <button type="button" className="btn btn--primary btn--block" disabled={!canReserve || submitting} onClick={reserve}>
                  {soldOut ? '품절' : closed ? '예약 마감' : store.shutdown ? '영업 종료' : submitting ? '예약 중...' : user ? '예약하기' : '로그인하고 예약하기'}
                </button>
                <p style={{ margin: 0, fontSize: 12, color: 'var(--ink-500)', textAlign: 'center' }}>
                  결제는 매장에서 픽업할 때 진행합니다. 1인 최대 {MAX_QTY}개까지 예약할 수 있어요.
                </p>
              </>
            )}
          </div>
        </div>
      </div>
      <Toast message={toast} onDone={clearToast} />
    </>
  )
}
