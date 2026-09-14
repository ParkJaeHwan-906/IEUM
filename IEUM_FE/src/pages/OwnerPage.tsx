import { useCallback, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { approveOrder, createItem, createStore, myStores, ownerOrders, pickupOrder, readyOrder } from '../api/owner'
import { listStoreItems } from '../api/stores'
import { messageOf } from '../api/errors'
import { Empty, Skeleton, StateBadge, Toast, useAsync } from '../components/ui'
import { activeStates, dateTime, storeTypeEmoji, storeTypeLabel, time, won } from '../lib/format'
import type { CreateItemRequest, CreateStoreRequest, ItemResponse, OrderResponse, StoreResponse, StoreType } from '../types/api'

type Tab = 'orders' | 'stores'

const storeTypes: StoreType[] = ['FOOD', 'CAFE', 'BAKERY', 'FAST_FOOD', 'FOOD_INGREDIENTS', 'BAR']

function defaultLastOrderTime() {
  const d = new Date()
  d.setHours(d.getHours() + 3, 0, 0, 0)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export default function OwnerPage() {
  const [tab, setTab] = useState<Tab>('orders')
  const [toast, setToast] = useState<string | null>(null)
  const clearToast = useCallback(() => setToast(null), [])

  return (
    <section className="section">
      <div className="section__head">
        <h2>점주 센터</h2>
      </div>
      <div className="tabs">
        <button type="button" className={tab === 'orders' ? 'active' : ''} onClick={() => setTab('orders')}>
          예약 관리
        </button>
        <button type="button" className={tab === 'stores' ? 'active' : ''} onClick={() => setTab('stores')}>
          매장 · 상품
        </button>
      </div>
      {tab === 'orders' ? <OrdersTab notify={setToast} /> : <StoresTab notify={setToast} />}
      <Toast message={toast} onDone={clearToast} />
    </section>
  )
}

function OrdersTab({ notify }: { notify: (m: string) => void }) {
  const [filter, setFilter] = useState<'active' | 'all'>('active')
  const [busy, setBusy] = useState<number | null>(null)
  const { data, loading, error, setData } = useAsync(() => ownerOrders(), [])

  const list = useMemo(() => {
    const orders = data ?? []
    return filter === 'active' ? orders.filter((o) => activeStates.includes(o.state)) : orders
  }, [data, filter])

  const counts = useMemo(() => {
    const orders = data ?? []
    return {
      pending: orders.filter((o) => o.state === 'PENDING').length,
      approved: orders.filter((o) => o.state === 'APPROVED').length,
      ready: orders.filter((o) => o.state === 'READY_FOR_PICKUP').length,
    }
  }, [data])

  async function run(order: OrderResponse, action: (id: number) => Promise<OrderResponse>, label: string) {
    setBusy(order.orderId)
    try {
      const updated = await action(order.orderId)
      setData((data ?? []).map((o) => (o.orderId === updated.orderId ? updated : o)))
      notify(`${label} 처리했습니다.`)
    } catch (e) {
      notify(messageOf(e))
    } finally {
      setBusy(null)
    }
  }

  return (
    <>
      <dl className="kv" style={{ marginTop: 0, gridTemplateColumns: 'repeat(3, 1fr)', marginBottom: 16 }}>
        <div>
          <dt>승인 대기</dt>
          <dd style={{ fontSize: 20 }}>{counts.pending}</dd>
        </div>
        <div>
          <dt>준비 중</dt>
          <dd style={{ fontSize: 20 }}>{counts.approved}</dd>
        </div>
        <div>
          <dt>픽업 대기</dt>
          <dd style={{ fontSize: 20 }}>{counts.ready}</dd>
        </div>
      </dl>
      <div className="chips" style={{ marginBottom: 12 }}>
        <button type="button" className={`chip ${filter === 'active' ? 'active' : ''}`} onClick={() => setFilter('active')}>
          진행 중
        </button>
        <button type="button" className={`chip ${filter === 'all' ? 'active' : ''}`} onClick={() => setFilter('all')}>
          전체
        </button>
      </div>
      {error ? <div className="alert alert--error">{messageOf(error)}</div> : null}
      <div className="stack">
        {loading && <Skeleton count={3} height={120} />}
        {list.map((order) => (
          <div key={order.orderId} className="card order-card">
            <div className="order-card__head">
              <div>
                <div className="order-card__title">{order.itemName}</div>
                <div className="order-card__sub">
                  예약번호 {order.orderId} · {dateTime(order.createdAt)} · {order.quantity}개 · {won(order.orderPrice * order.quantity)}
                </div>
              </div>
              <StateBadge state={order.state} />
            </div>
            {activeStates.includes(order.state) && (
              <div className="order-card__foot" style={{ justifyContent: 'flex-end' }}>
                {order.state === 'PENDING' && (
                  <button type="button" className="btn btn--teal btn--sm" disabled={busy === order.orderId} onClick={() => run(order, approveOrder, '승인')}>
                    승인
                  </button>
                )}
                {order.state === 'APPROVED' && (
                  <button type="button" className="btn btn--teal btn--sm" disabled={busy === order.orderId} onClick={() => run(order, readyOrder, '준비 완료')}>
                    준비 완료
                  </button>
                )}
                {order.state === 'READY_FOR_PICKUP' && (
                  <button type="button" className="btn btn--primary btn--sm" disabled={busy === order.orderId} onClick={() => run(order, pickupOrder, '픽업 완료')}>
                    픽업 완료
                  </button>
                )}
              </div>
            )}
          </div>
        ))}
      </div>
      {!loading && !error && list.length === 0 && <Empty icon="📭" title="처리할 예약이 없어요" />}
    </>
  )
}

function StoresTab({ notify }: { notify: (m: string) => void }) {
  const { data: stores, loading, error, setData } = useAsync(() => myStores(), [])
  const [showStoreForm, setShowStoreForm] = useState(false)

  return (
    <>
      {error ? <div className="alert alert--error">{messageOf(error)}</div> : null}
      <div className="stack">
        {loading && <Skeleton count={2} height={160} />}
        {stores?.map((store) => (
          <StorePanel key={store.storeUid} store={store} notify={notify} />
        ))}
      </div>
      {!loading && !error && stores?.length === 0 && !showStoreForm && (
        <Empty icon="🏪" title="등록된 매장이 없어요">
          <button type="button" className="btn btn--teal btn--sm" onClick={() => setShowStoreForm(true)}>
            첫 매장 등록하기
          </button>
        </Empty>
      )}
      <div style={{ marginTop: 16 }}>
        {showStoreForm ? (
          <StoreForm
            onCancel={() => setShowStoreForm(false)}
            onCreated={(store) => {
              setData([...(stores ?? []), store])
              setShowStoreForm(false)
              notify('매장을 등록했습니다.')
            }}
          />
        ) : (
          (stores?.length ?? 0) > 0 && (
            <button type="button" className="btn btn--ghost btn--block" onClick={() => setShowStoreForm(true)}>
              + 매장 추가
            </button>
          )
        )}
      </div>
    </>
  )
}

function StorePanel({ store, notify }: { store: StoreResponse; notify: (m: string) => void }) {
  const { data: items, loading, setData } = useAsync(() => listStoreItems(store.storeUid), [store.storeUid])
  const [showItemForm, setShowItemForm] = useState(false)

  return (
    <div className="card" style={{ padding: 14 }}>
      <div className="store-card" style={{ padding: 0, marginBottom: 12 }}>
        <div className="store-card__logo">{store.logoImgUrl ? <img src={store.logoImgUrl} alt="" /> : storeTypeEmoji[store.storeType]}</div>
        <div className="store-card__body">
          <div className="store-card__name">
            <Link to={`/stores/${store.storeUid}`}>{store.name}</Link>
            {store.shutdown && <span className="badge badge--state">영업 종료</span>}
          </div>
          <div className="store-card__sub">
            {storeTypeLabel[store.storeType]} · {time(store.openAt)} ~ {time(store.closeAt)}
          </div>
        </div>
        <button type="button" className="btn btn--teal btn--sm" onClick={() => setShowItemForm((v) => !v)}>
          {showItemForm ? '닫기' : '+ 상품'}
        </button>
      </div>
      {showItemForm && (
        <ItemForm
          storeUid={store.storeUid}
          onCreated={(item) => {
            setData([...(items ?? []), item])
            setShowItemForm(false)
            notify('상품을 등록했습니다.')
          }}
        />
      )}
      <div className="divider" />
      {loading && <Skeleton count={1} height={40} />}
      {items && items.length === 0 && <div style={{ fontSize: 13, color: 'var(--ink-500)' }}>등록된 상품이 없습니다.</div>}
      <div className="stack" style={{ gap: 8 }}>
        {items?.map((item) => (
          <div key={item.itemUid} className="order-card__head" style={{ fontSize: 14 }}>
            <div>
              <Link to={`/items/${item.itemUid}`} style={{ fontWeight: 700 }}>
                {item.name}
              </Link>
              <div className="order-card__sub">
                {won(item.salePrice)} · 마감 {dateTime(item.lastOrderTime)}
              </div>
            </div>
            <span style={{ fontWeight: 700, color: item.remainingQuantity === 0 ? 'var(--ink-300)' : 'var(--teal-700)' }}>
              {item.remainingQuantity}/{item.initialQuantity}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

function StoreForm({ onCreated, onCancel }: { onCreated: (s: StoreResponse) => void; onCancel: () => void }) {
  const [form, setForm] = useState<CreateStoreRequest>({ name: '', storeType: 'FOOD', openAt: '09:00', closeAt: '21:00', logoImgUrl: '' })
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      onCreated(await createStore({ ...form, logoImgUrl: form.logoImgUrl || undefined }))
    } catch (err) {
      setError(messageOf(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className="card" style={{ padding: 16, display: 'grid', gap: 12 }} onSubmit={submit}>
      <b>매장 등록</b>
      <div className="field">
        <label>매장 이름</label>
        <input required maxLength={20} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
      </div>
      <div className="field">
        <label>업종</label>
        <select value={form.storeType} onChange={(e) => setForm({ ...form, storeType: e.target.value as StoreType })}>
          {storeTypes.map((t) => (
            <option key={t} value={t}>
              {storeTypeEmoji[t]} {storeTypeLabel[t]}
            </option>
          ))}
        </select>
      </div>
      <div className="two-col" style={{ gap: 12 }}>
        <div className="field">
          <label>오픈</label>
          <input type="time" required value={form.openAt} onChange={(e) => setForm({ ...form, openAt: e.target.value })} />
        </div>
        <div className="field">
          <label>마감</label>
          <input type="time" required value={form.closeAt} onChange={(e) => setForm({ ...form, closeAt: e.target.value })} />
        </div>
      </div>
      <div className="field">
        <label>로고 이미지 URL (선택)</label>
        <input maxLength={255} value={form.logoImgUrl} onChange={(e) => setForm({ ...form, logoImgUrl: e.target.value })} />
      </div>
      {error && <div className="alert alert--error">{error}</div>}
      <div className="two-col" style={{ gap: 8 }}>
        <button type="button" className="btn btn--ghost" onClick={onCancel}>
          취소
        </button>
        <button type="submit" className="btn btn--teal" disabled={submitting}>
          등록
        </button>
      </div>
    </form>
  )
}

function ItemForm({ storeUid, onCreated }: { storeUid: string; onCreated: (i: ItemResponse) => void }) {
  const [form, setForm] = useState<CreateItemRequest>({
    name: '',
    originalPrice: 10000,
    salePrice: 5000,
    initialQuantity: 10,
    lastOrderTime: defaultLastOrderTime(),
    itemImgUrl: '',
  })
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (form.salePrice > form.originalPrice) {
      setError('할인가는 정가보다 클 수 없습니다.')
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      onCreated(await createItem(storeUid, { ...form, lastOrderTime: `${form.lastOrderTime}:00`, itemImgUrl: form.itemImgUrl || undefined }))
    } catch (err) {
      setError(messageOf(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form style={{ display: 'grid', gap: 10, padding: 12, background: 'var(--teal-50)', borderRadius: 'var(--radius-sm)', marginBottom: 12 }} onSubmit={submit}>
      <b>마감 상품 등록</b>
      <div className="field">
        <label>상품명</label>
        <input required maxLength={50} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
      </div>
      <div className="two-col" style={{ gap: 10 }}>
        <div className="field">
          <label>정가</label>
          <input type="number" min={0} required value={form.originalPrice} onChange={(e) => setForm({ ...form, originalPrice: Number(e.target.value) })} />
        </div>
        <div className="field">
          <label>할인가</label>
          <input type="number" min={0} required value={form.salePrice} onChange={(e) => setForm({ ...form, salePrice: Number(e.target.value) })} />
        </div>
      </div>
      <div className="two-col" style={{ gap: 10 }}>
        <div className="field">
          <label>수량</label>
          <input type="number" min={1} max={10000} required value={form.initialQuantity} onChange={(e) => setForm({ ...form, initialQuantity: Number(e.target.value) })} />
        </div>
        <div className="field">
          <label>예약 마감 시각</label>
          <input type="datetime-local" required value={form.lastOrderTime} onChange={(e) => setForm({ ...form, lastOrderTime: e.target.value })} />
        </div>
      </div>
      <div className="field">
        <label>상품 이미지 URL (선택)</label>
        <input maxLength={500} value={form.itemImgUrl} onChange={(e) => setForm({ ...form, itemImgUrl: e.target.value })} />
      </div>
      {error && <div className="alert alert--error">{error}</div>}
      <button type="submit" className="btn btn--teal" disabled={submitting}>
        상품 등록
      </button>
    </form>
  )
}
