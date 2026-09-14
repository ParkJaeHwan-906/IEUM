import { useCallback, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { cancelOrder, myOrders } from '../api/orders'
import { messageOf } from '../api/errors'
import { Empty, Skeleton, StateBadge, Toast, useAsync } from '../components/ui'
import { activeStates, dateTime, won } from '../lib/format'
import type { OrderResponse } from '../types/api'

type Tab = 'active' | 'done'

function pickupCode(orderId: number) {
  return String((orderId * 7919) % 10000).padStart(4, '0')
}

export default function OrdersPage() {
  const [tab, setTab] = useState<Tab>('active')
  const [busy, setBusy] = useState<number | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const { data, loading, error, setData } = useAsync(() => myOrders(), [])
  const clearToast = useCallback(() => setToast(null), [])

  const list = useMemo(() => {
    const orders = data ?? []
    return tab === 'active' ? orders.filter((o) => activeStates.includes(o.state)) : orders.filter((o) => !activeStates.includes(o.state))
  }, [data, tab])

  async function cancel(order: OrderResponse) {
    setBusy(order.orderId)
    try {
      const updated = await cancelOrder(order.orderId)
      setData((data ?? []).map((o) => (o.orderId === updated.orderId ? updated : o)))
      setToast('예약을 취소했습니다.')
    } catch (e) {
      setToast(messageOf(e))
    } finally {
      setBusy(null)
    }
  }

  return (
    <section className="section">
      <div className="section__head">
        <h2>내 예약</h2>
      </div>
      <div className="tabs">
        <button type="button" className={tab === 'active' ? 'active' : ''} onClick={() => setTab('active')}>
          진행 중
        </button>
        <button type="button" className={tab === 'done' ? 'active' : ''} onClick={() => setTab('done')}>
          지난 예약
        </button>
      </div>
      {error ? <div className="alert alert--error">{messageOf(error)}</div> : null}
      <div className="stack">
        {loading && <Skeleton count={3} height={130} />}
        {list.map((order) => (
          <div key={order.orderId} className="card order-card">
            <div className="order-card__head">
              <div>
                <div className="order-card__title">
                  <Link to={`/items/${order.itemUid}`}>{order.itemName}</Link>
                </div>
                <div className="order-card__sub">
                  예약번호 {order.orderId} · {dateTime(order.createdAt)}
                </div>
              </div>
              <StateBadge state={order.state} />
            </div>
            <div className="order-card__foot">
              <span>
                {won(order.orderPrice)} × {order.quantity}개 = <b>{won(order.orderPrice * order.quantity)}</b>
              </span>
              {order.state === 'READY_FOR_PICKUP' && (
                <span>
                  픽업 코드 <span className="pickup-code">{pickupCode(order.orderId)}</span>
                </span>
              )}
              {activeStates.includes(order.state) && (
                <button type="button" className="btn btn--danger btn--sm" disabled={busy === order.orderId} onClick={() => cancel(order)}>
                  예약 취소
                </button>
              )}
            </div>
            {order.state === 'READY_FOR_PICKUP' && order.readyAt && (
              <div className="alert alert--info">
                {dateTime(order.readyAt)}부터 픽업 가능합니다. 15분 안에 방문하지 않으면 예약이 만료됩니다.
              </div>
            )}
            {order.state === 'PENDING' && <div className="alert alert--info">점주가 예약을 확인하는 중입니다.</div>}
          </div>
        ))}
      </div>
      {!loading && !error && list.length === 0 && (
        <Empty icon="🧾" title={tab === 'active' ? '진행 중인 예약이 없어요' : '지난 예약이 없어요'}>
          <Link to="/" className="btn btn--primary btn--sm">
            상품 둘러보기
          </Link>
        </Empty>
      )}
      <Toast message={toast} onDone={clearToast} />
    </section>
  )
}
