import { useCallback, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import {
  approveOrder,
  closeItem,
  createItem,
  createStore,
  itemOrders,
  myStores,
  pickupOrder,
  readyOrder,
  rejectOrder,
  shutdownStore,
  updateItemQuantity,
  updateStoreHours,
} from '../api/owner'
import { listStoreItems } from '../api/stores'
import { messageOf } from '../api/errors'
import { Empty, Skeleton, StateBadge, Toast, useAsync } from '../components/ui'
import { dateTime, orderStateLabel, storeTypeEmoji, storeTypeLabel, time, won } from '../lib/format'
import type { CreateItemRequest, CreateStoreRequest, ItemResponse, OrderResponse, OrderState, StoreResponse, StoreType } from '../types/api'

type Tab = 'orders' | 'stores'

interface Selection {
  storeUid: string
  itemUid: string
}

const storeTypes: StoreType[] = ['FOOD', 'CAFE', 'BAKERY', 'FAST_FOOD', 'FOOD_INGREDIENTS', 'BAR']
const orderStates: OrderState[] = ['PENDING', 'APPROVED', 'READY_FOR_PICKUP', 'PICKED_UP', 'CANCELED', 'EXPIRED']

function defaultLastOrderTime() {
  const d = new Date()
  d.setHours(d.getHours() + 3, 0, 0, 0)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function isClosed(item: ItemResponse) {
  return new Date(item.lastOrderTime).getTime() <= Date.now()
}

export default function OwnerPage() {
  const [tab, setTab] = useState<Tab>('orders')
  const [selection, setSelection] = useState<Selection | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const clearToast = useCallback(() => setToast(null), [])

  function showOrders(next: Selection) {
    setSelection(next)
    setTab('orders')
  }

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
      {tab === 'orders' ? (
        <OrdersTab selection={selection} onSelect={setSelection} notify={setToast} />
      ) : (
        <StoresTab notify={setToast} onShowOrders={showOrders} />
      )}
      <Toast message={toast} onDone={clearToast} />
    </section>
  )
}

function OrdersTab({
  selection,
  onSelect,
  notify,
}: {
  selection: Selection | null
  onSelect: (s: Selection | null) => void
  notify: (m: string) => void
}) {
  const [storeUid, setStoreUid] = useState(selection?.storeUid ?? '')
  const [itemUid, setItemUid] = useState(selection?.itemUid ?? '')
  const [filter, setFilter] = useState<OrderState | 'ALL'>('ALL')
  const [busy, setBusy] = useState<number | null>(null)

  const stores = useAsync(() => myStores(), [])
  const activeStore = storeUid || stores.data?.[0]?.storeUid || ''
  const items = useAsync(() => (activeStore ? listStoreItems(activeStore) : Promise.resolve([])), [activeStore])
  const storeItems = (items.data ?? []).filter((i) => i.storeUid === activeStore)
  const activeItem = storeItems.some((i) => i.itemUid === itemUid) ? itemUid : (storeItems[0]?.itemUid ?? '')
  const orders = useAsync(
    () => (activeStore && activeItem ? itemOrders(activeStore, activeItem, filter === 'ALL' ? undefined : filter) : Promise.resolve([])),
    [activeStore, activeItem, filter],
  )

  function selectStore(next: string) {
    setStoreUid(next)
    setItemUid('')
    onSelect({ storeUid: next, itemUid: '' })
  }

  function selectItem(next: string) {
    setItemUid(next)
    onSelect({ storeUid: activeStore, itemUid: next })
  }

  async function run(order: OrderResponse, action: () => Promise<OrderResponse>, label: string) {
    setBusy(order.orderId)
    try {
      const updated = await action()
      const list = orders.data ?? []
      orders.setData(
        filter === 'ALL' || updated.state === filter
          ? list.map((o) => (o.orderId === updated.orderId ? updated : o))
          : list.filter((o) => o.orderId !== updated.orderId),
      )
      notify(`${label} 처리했습니다.`)
      return true
    } catch (e) {
      notify(messageOf(e))
      return false
    } finally {
      setBusy(null)
    }
  }

  if (stores.error) return <div className="alert alert--error">{messageOf(stores.error)}</div>
  if (stores.loading) return <Skeleton count={2} height={60} />
  if ((stores.data ?? []).length === 0) return <Empty icon="🏪" title="먼저 매장 · 상품 탭에서 매장을 등록해 주세요" />

  return (
    <>
      <div className="two-col" style={{ gap: 10, marginBottom: 12 }}>
        <div className="field">
          <label>매장</label>
          <select value={activeStore} onChange={(e) => selectStore(e.target.value)}>
            {stores.data?.map((s) => (
              <option key={s.storeUid} value={s.storeUid}>
                {s.name}
                {s.shutdown ? ' (영업 종료)' : ''}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label>상품</label>
          <select value={activeItem} onChange={(e) => selectItem(e.target.value)} disabled={storeItems.length === 0}>
            {storeItems.length === 0 && <option value="">등록된 상품 없음</option>}
            {storeItems.map((i) => (
              <option key={i.itemUid} value={i.itemUid}>
                {i.name} ({i.remainingQuantity}/{i.initialQuantity})
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="chips" style={{ marginBottom: 12 }}>
        <button type="button" className={`chip ${filter === 'ALL' ? 'active' : ''}`} onClick={() => setFilter('ALL')}>
          전체
        </button>
        {orderStates.map((s) => (
          <button key={s} type="button" className={`chip ${filter === s ? 'active' : ''}`} onClick={() => setFilter(s)}>
            {orderStateLabel[s]}
          </button>
        ))}
        <button type="button" className="chip" onClick={orders.reload}>
          새로고침
        </button>
      </div>
      <p style={{ margin: '0 0 12px', fontSize: 12, color: 'var(--ink-500)' }}>
        승인 대기 예약은 5분 안에 승인하지 않으면 자동 취소됩니다. 픽업 가능 상태가 된 뒤 15분 안에 픽업하지 않으면 만료됩니다.
      </p>
      {orders.error ? <div className="alert alert--error">{messageOf(orders.error)}</div> : null}
      {items.error ? <div className="alert alert--error">{messageOf(items.error)}</div> : null}
      <div className="stack">
        {orders.loading && <Skeleton count={3} height={120} />}
        {!orders.loading &&
          orders.data?.map((order) => (
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
              {order.state === 'PENDING' && (
                <div className="order-card__foot" style={{ justifyContent: 'flex-end', gap: 8 }}>
                  <button
                    type="button"
                    className="btn btn--danger btn--sm"
                    disabled={busy === order.orderId}
                    onClick={() => run(order, () => rejectOrder(order.orderId), '거절')}
                  >
                    거절
                  </button>
                  <button
                    type="button"
                    className="btn btn--teal btn--sm"
                    disabled={busy === order.orderId}
                    onClick={() => run(order, () => approveOrder(order.orderId), '승인')}
                  >
                    승인
                  </button>
                </div>
              )}
              {order.state === 'APPROVED' && (
                <div className="order-card__foot" style={{ justifyContent: 'flex-end' }}>
                  <button
                    type="button"
                    className="btn btn--teal btn--sm"
                    disabled={busy === order.orderId}
                    onClick={() => run(order, () => readyOrder(order.orderId), '준비 완료')}
                  >
                    준비 완료
                  </button>
                </div>
              )}
              {order.state === 'READY_FOR_PICKUP' && (
                <PickupForm
                  busy={busy === order.orderId}
                  readyAt={order.readyAt}
                  onSubmit={(code) => run(order, () => pickupOrder(order.orderId, code), '픽업 완료')}
                />
              )}
            </div>
          ))}
      </div>
      {!orders.loading && !orders.error && activeItem && (orders.data ?? []).length === 0 && <Empty icon="📭" title="해당 상태의 예약이 없어요" />}
    </>
  )
}

function PickupForm({ busy, readyAt, onSubmit }: { busy: boolean; readyAt?: string; onSubmit: (code: string) => Promise<boolean> }) {
  const [code, setCode] = useState('')

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (await onSubmit(code)) setCode('')
  }

  return (
    <form className="order-card__foot" style={{ gap: 8, flexWrap: 'wrap' }} onSubmit={submit}>
      <span style={{ fontSize: 13, color: 'var(--ink-500)' }}>{readyAt ? `${dateTime(readyAt)} 준비 완료` : '픽업 대기'}</span>
      <div style={{ display: 'flex', gap: 8, marginLeft: 'auto' }}>
        <input
          required
          inputMode="numeric"
          pattern="\d{6}"
          maxLength={6}
          placeholder="픽업 코드 6자리"
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
          style={{ width: 140 }}
        />
        <button type="submit" className="btn btn--primary btn--sm" disabled={busy || code.length !== 6}>
          픽업 확인
        </button>
      </div>
    </form>
  )
}

function StoresTab({ notify, onShowOrders }: { notify: (m: string) => void; onShowOrders: (s: Selection) => void }) {
  const { data: stores, loading, error, setData } = useAsync(() => myStores(), [])
  const [showStoreForm, setShowStoreForm] = useState(false)

  function replace(store: StoreResponse) {
    setData((stores ?? []).map((s) => (s.storeUid === store.storeUid ? store : s)))
  }

  return (
    <>
      {error ? <div className="alert alert--error">{messageOf(error)}</div> : null}
      <div className="stack">
        {loading && <Skeleton count={2} height={160} />}
        {stores?.map((store) => (
          <StorePanel key={store.storeUid} store={store} notify={notify} onChange={replace} onShowOrders={onShowOrders} />
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

type Panel = 'none' | 'item' | 'hours'

function StorePanel({
  store,
  notify,
  onChange,
  onShowOrders,
}: {
  store: StoreResponse
  notify: (m: string) => void
  onChange: (s: StoreResponse) => void
  onShowOrders: (s: Selection) => void
}) {
  const { data: items, loading, error, setData } = useAsync(() => listStoreItems(store.storeUid), [store.storeUid])
  const [panel, setPanel] = useState<Panel>('none')
  const [busy, setBusy] = useState(false)

  function toggle(next: Panel) {
    setPanel((p) => (p === next ? 'none' : next))
  }

  function replaceItem(item: ItemResponse) {
    setData((items ?? []).map((i) => (i.itemUid === item.itemUid ? item : i)))
  }

  async function shutdown() {
    if (!window.confirm(`'${store.name}' 매장의 영업을 종료할까요? 종료하면 되돌릴 수 없습니다.`)) return
    setBusy(true)
    try {
      onChange(await shutdownStore(store.storeUid))
      notify('영업을 종료했습니다.')
    } catch (e) {
      notify(messageOf(e))
    } finally {
      setBusy(false)
    }
  }

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
      </div>
      {!store.shutdown && (
        <div className="chips" style={{ marginBottom: 12 }}>
          <button type="button" className={`chip ${panel === 'item' ? 'active' : ''}`} onClick={() => toggle('item')}>
            + 상품 등록
          </button>
          <button type="button" className={`chip ${panel === 'hours' ? 'active' : ''}`} onClick={() => toggle('hours')}>
            영업시간 변경
          </button>
          <button type="button" className="chip" disabled={busy} onClick={shutdown}>
            영업 종료
          </button>
        </div>
      )}
      {panel === 'item' && (
        <ItemForm
          storeUid={store.storeUid}
          onCreated={(item) => {
            setData([...(items ?? []), item])
            setPanel('none')
            notify('상품을 등록했습니다.')
          }}
        />
      )}
      {panel === 'hours' && (
        <HoursForm
          store={store}
          onSaved={(updated) => {
            onChange(updated)
            setPanel('none')
            notify('영업시간을 변경했습니다.')
          }}
        />
      )}
      <div className="divider" />
      {error ? <div className="alert alert--error">{messageOf(error)}</div> : null}
      {loading && <Skeleton count={1} height={40} />}
      {items && items.length === 0 && <div style={{ fontSize: 13, color: 'var(--ink-500)' }}>등록된 상품이 없습니다.</div>}
      <div className="stack" style={{ gap: 12 }}>
        {items?.map((item) => (
          <ItemRow
            key={item.itemUid}
            item={item}
            notify={notify}
            onChange={replaceItem}
            onShowOrders={() => onShowOrders({ storeUid: store.storeUid, itemUid: item.itemUid })}
          />
        ))}
      </div>
    </div>
  )
}

function ItemRow({
  item,
  notify,
  onChange,
  onShowOrders,
}: {
  item: ItemResponse
  notify: (m: string) => void
  onChange: (i: ItemResponse) => void
  onShowOrders: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [quantity, setQuantity] = useState(item.initialQuantity)
  const [busy, setBusy] = useState(false)
  const closed = isClosed(item)
  const reserved = item.initialQuantity - item.remainingQuantity

  async function act(action: () => Promise<ItemResponse>, done: string) {
    setBusy(true)
    try {
      onChange(await action())
      setEditing(false)
      notify(done)
    } catch (e) {
      notify(messageOf(e))
    } finally {
      setBusy(false)
    }
  }

  function saveQuantity(e: FormEvent) {
    e.preventDefault()
    act(() => updateItemQuantity(item.storeUid, item.itemUid, quantity), '수량을 변경했습니다.')
  }

  function close() {
    if (!window.confirm(`'${item.name}' 판매를 지금 마감할까요?`)) return
    act(() => closeItem(item.storeUid, item.itemUid), '판매를 마감했습니다.')
  }

  return (
    <div style={{ fontSize: 14 }}>
      <div className="order-card__head">
        <div>
          <Link to={`/items/${item.itemUid}`} style={{ fontWeight: 700 }}>
            {item.name}
          </Link>
          <div className="order-card__sub">
            {won(item.salePrice)} · {closed ? '판매 마감' : `마감 ${dateTime(item.lastOrderTime)}`} · 예약 {reserved}개
          </div>
        </div>
        <span style={{ fontWeight: 700, color: item.remainingQuantity === 0 ? 'var(--ink-300)' : 'var(--teal-700)' }}>
          {item.remainingQuantity}/{item.initialQuantity}
        </span>
      </div>
      <div className="chips" style={{ marginTop: 6 }}>
        <button type="button" className="chip" onClick={onShowOrders}>
          예약 보기
        </button>
        <button
          type="button"
          className={`chip ${editing ? 'active' : ''}`}
          onClick={() => {
            setQuantity(item.initialQuantity)
            setEditing((v) => !v)
          }}
        >
          수량 조정
        </button>
        {!closed && (
          <button type="button" className="chip" disabled={busy} onClick={close}>
            판매 마감
          </button>
        )}
      </div>
      {editing && (
        <form style={{ display: 'flex', gap: 8, alignItems: 'flex-end', marginTop: 8 }} onSubmit={saveQuantity}>
          <div className="field" style={{ flex: 1 }}>
            <label>총 수량 (예약된 {reserved}개 이상)</label>
            <input
              type="number"
              min={Math.max(1, reserved)}
              max={10000}
              required
              value={quantity}
              onChange={(e) => setQuantity(Number(e.target.value))}
            />
          </div>
          <button type="submit" className="btn btn--teal btn--sm" disabled={busy}>
            저장
          </button>
        </form>
      )}
    </div>
  )
}

function HoursForm({ store, onSaved }: { store: StoreResponse; onSaved: (s: StoreResponse) => void }) {
  const [openAt, setOpenAt] = useState(time(store.openAt))
  const [closeAt, setCloseAt] = useState(time(store.closeAt))
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      onSaved(await updateStoreHours(store.storeUid, { openAt: `${openAt}:00`, closeAt: `${closeAt}:00` }))
    } catch (err) {
      setError(messageOf(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form style={{ display: 'grid', gap: 10, padding: 12, background: 'var(--teal-50)', borderRadius: 'var(--radius-sm)', marginBottom: 12 }} onSubmit={submit}>
      <b>영업시간 변경</b>
      <div className="two-col" style={{ gap: 10 }}>
        <div className="field">
          <label>오픈</label>
          <input type="time" required value={openAt} onChange={(e) => setOpenAt(e.target.value)} />
        </div>
        <div className="field">
          <label>마감</label>
          <input type="time" required value={closeAt} onChange={(e) => setCloseAt(e.target.value)} />
        </div>
      </div>
      {error && <div className="alert alert--error">{error}</div>}
      <button type="submit" className="btn btn--teal" disabled={submitting}>
        저장
      </button>
    </form>
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
      onCreated(
        await createStore({
          ...form,
          openAt: `${form.openAt}:00`,
          closeAt: `${form.closeAt}:00`,
          logoImgUrl: form.logoImgUrl || undefined,
        }),
      )
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
    if (new Date(form.lastOrderTime).getTime() <= Date.now()) {
      setError('예약 마감 시각은 현재 이후여야 합니다.')
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
