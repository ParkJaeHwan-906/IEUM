import { useCallback, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { cancelOrder, myOrders } from '../api/orders'
import { messageOf } from '../api/errors'
import { Empty, Skeleton, StateBadge, Toast, useAsync } from '../components/ui'
import { activeStates, dateTime, won } from '../lib/format'
import type { OrderResponse, OrderState } from '../types/api'

type Tab = 'active' | 'done'

const cancelableStates: OrderState[] = ['PENDING', 'APPROVED']

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
              {order.state === 'READY_FOR_PICKUP' && order.pickupCode && (
                <span>
                  픽업 코드 <span className="pickup-code" style={{ fontSize: 22 }}>{order.pickupCode}</span>
                </span>
              )}
              {cancelableStates.includes(order.state) && (
                <button type="button" className="btn btn--danger btn--sm" disabled={busy === order.orderId} onClick={() => cancel(order)}>
                  예약 취소
                </button>
              )}
            </div>
            {order.state === 'READY_FOR_PICKUP' && (
              <div className="alert alert--info">
                {order.readyAt ? `${dateTime(order.readyAt)}부터 ` : ''}픽업 가능합니다. 매장에서 픽업 코드를 알려 주세요. 15분 안에 픽업하지 않으면 예약이
                만료되며, 이 단계에서는 취소할 수 없습니다.
              </div>
            )}
            {order.state === 'PENDING' && (
              <div className="alert alert--info">점주가 예약을 확인하는 중입니다. 5분 안에 승인되지 않으면 자동 취소되고 재고가 복구됩니다.</div>
            )}
            {order.state === 'APPROVED' && <div className="alert alert--info">점주가 상품을 준비하고 있습니다. 준비가 끝나면 픽업 코드가 발급됩니다.</div>}
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
