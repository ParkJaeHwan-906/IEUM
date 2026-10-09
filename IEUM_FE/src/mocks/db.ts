import { ApiError } from '../api/errors'
import type {
  CreateItemRequest,
  CreateOrderRequest,
  CreateStoreRequest,
  ItemResponse,
  OrderResponse,
  OrderState,
  PickupRequest,
  StoreResponse,
  UpdateItemQuantityRequest,
  UpdateStoreHoursRequest,
} from '../types/api'
import { seedItems, seedOrders, seedOwnerStoreUids, seedStores, toLocalIso } from './seed'

const KEY = 'ieum.mock.v2'
const PENDING_TIMEOUT_MS = 5 * 60 * 1000
const PICKUP_TIMEOUT_MS = 15 * 60 * 1000
const MAX_ORDER_QUANTITY = 10
const activeStates: OrderState[] = ['PENDING', 'APPROVED', 'READY_FOR_PICKUP']

interface MockState {
  stores: StoreResponse[]
  items: ItemResponse[]
  orders: OrderResponse[]
  ownerStoreUids: string[]
  idempotency: Record<string, number>
  nextOrderId: number
}

function fresh(): MockState {
  return {
    stores: structuredClone(seedStores),
    items: structuredClone(seedItems),
    orders: structuredClone(seedOrders),
    ownerStoreUids: [...seedOwnerStoreUids],
    idempotency: {},
    nextOrderId: 2000,
  }
}

function load(): MockState {
  try {
    const raw = localStorage.getItem(KEY)
    if (raw) return JSON.parse(raw) as MockState
  } catch {
    return fresh()
  }
  return fresh()
}

let state: MockState = load()

function persist() {
  try {
    localStorage.setItem(KEY, JSON.stringify(state))
  } catch {
    return
  }
}

function delay<T>(value: T, ms = 250): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(structuredClone(value)), ms))
}

function fail(status: number, title: string, detail: string, instance: string): never {
  throw new ApiError(status, { type: 'about:blank', title, status, detail, instance }, detail)
}

function badRequest(detail: string, instance: string): never {
  return fail(400, 'Bad Request', detail, instance)
}

function notFound(detail: string, instance: string): never {
  return fail(404, 'Not Found', detail, instance)
}

function conflict(detail: string, instance: string): never {
  return fail(409, 'Conflict', detail, instance)
}

function uid(prefix: string) {
  return `${prefix}-${Math.random().toString(36).slice(2, 10)}`
}

function newPickupCode() {
  return String(Math.floor(Math.random() * 1_000_000)).padStart(6, '0')
}

function withSeconds(value: string) {
  return value.length === 5 || value.length === 16 ? `${value}:00` : value
}

function restoreStock(order: OrderResponse) {
  const item = state.items.find((i) => i.itemUid === order.itemUid)
  if (item) item.remainingQuantity = Math.min(item.initialQuantity, item.remainingQuantity + order.quantity)
}

function sweep() {
  const now = Date.now()
  let changed = false
  for (const order of state.orders) {
    if (order.state === 'PENDING' && new Date(order.createdAt).getTime() + PENDING_TIMEOUT_MS <= now) {
      order.state = 'CANCELED'
      restoreStock(order)
      changed = true
    } else if (order.state === 'READY_FOR_PICKUP' && order.readyAt && new Date(order.readyAt).getTime() + PICKUP_TIMEOUT_MS <= now) {
      order.state = 'EXPIRED'
      delete order.pickupCode
      changed = true
    }
  }
  if (changed) persist()
}

function consumerView(order: OrderResponse): OrderResponse {
  const { pickupCode, ...rest } = order
  return order.state === 'READY_FOR_PICKUP' && pickupCode ? { ...rest, pickupCode } : rest
}

function ownerView(order: OrderResponse): OrderResponse {
  const { pickupCode: _hidden, ...rest } = order
  return rest
}

export function resetMock() {
  state = fresh()
  persist()
}

export const mockStores = {
  list(): Promise<StoreResponse[]> {
    return delay([...state.stores].reverse().filter((s) => !s.shutdown).slice(0, 50))
  },
  get(storeUid: string): Promise<StoreResponse> {
    const store = state.stores.find((s) => s.storeUid === storeUid)
    if (!store) notFound('매장을 찾을 수 없습니다.', `/api/stores/${storeUid}`)
    return delay(store)
  },
  items(storeUid: string): Promise<ItemResponse[]> {
    return delay(state.items.filter((i) => i.storeUid === storeUid))
  },
  allItems(): Promise<ItemResponse[]> {
    return delay(state.items)
  },
  item(itemUid: string): Promise<ItemResponse> {
    const item = state.items.find((i) => i.itemUid === itemUid)
    if (!item) notFound('상품을 찾을 수 없습니다.', `/api/items/${itemUid}`)
    return delay(item)
  },
}

function transition(order: OrderResponse, expected: OrderState[], next: OrderState, action: string, instance: string) {
  if (!expected.includes(order.state)) {
    conflict(`현재 상태에서는 ${action}할 수 없습니다.`, instance)
  }
  order.state = next
}

export const mockOrders = {
  mine(): Promise<OrderResponse[]> {
    sweep()
    return delay([...state.orders].sort((a, b) => b.orderId - a.orderId).map(consumerView))
  },
  create(request: CreateOrderRequest, idempotencyKey: string): Promise<OrderResponse> {
    const instance = '/api/orders'
    sweep()
    const existingId = state.idempotency[idempotencyKey]
    if (existingId !== undefined) {
      const existing = state.orders.find((o) => o.orderId === existingId)
      if (existing) {
        if (existing.itemUid !== request.itemUid) conflict('이미 다른 요청에 사용된 Idempotency-Key 입니다.', instance)
        return delay(consumerView(existing))
      }
    }
    if (!Number.isInteger(request.quantity) || request.quantity < 1 || request.quantity > MAX_ORDER_QUANTITY) {
      badRequest(`수량은 1개 이상 ${MAX_ORDER_QUANTITY}개 이하여야 합니다.`, instance)
    }
    const item = state.items.find((i) => i.itemUid === request.itemUid)
    if (!item) notFound('상품을 찾을 수 없습니다.', instance)
    const store = state.stores.find((s) => s.storeUid === item.storeUid)
    if (!store || store.shutdown || new Date(item.lastOrderTime).getTime() <= Date.now()) {
      conflict('예약할 수 없는 상품입니다.', instance)
    }
    const active = state.orders.find((o) => o.itemUid === item.itemUid && activeStates.includes(o.state))
    if (active) conflict('이미 진행 중인 예약이 있는 상품입니다.', instance)
    if (item.remainingQuantity < request.quantity) conflict('재고가 부족합니다.', instance)
    item.remainingQuantity -= request.quantity
    const order: OrderResponse = {
      orderId: state.nextOrderId++,
      itemUid: item.itemUid,
      itemName: item.name,
      quantity: request.quantity,
      orderPrice: item.salePrice,
      state: 'PENDING',
      createdAt: toLocalIso(new Date()),
    }
    state.orders.push(order)
    state.idempotency[idempotencyKey] = order.orderId
    persist()
    return delay(consumerView(order))
  },
  cancel(orderId: number): Promise<OrderResponse> {
    const instance = `/api/orders/${orderId}/cancel`
    sweep()
    const order = state.orders.find((o) => o.orderId === orderId)
    if (!order) notFound('예약을 찾을 수 없습니다.', instance)
    transition(order, ['PENDING', 'APPROVED'], 'CANCELED', '취소', instance)
    restoreStock(order)
    persist()
    return delay(consumerView(order))
  },
}

function ownedStore(storeUid: string, instance: string): StoreResponse {
  const store = state.stores.find((s) => s.storeUid === storeUid)
  if (!store || !state.ownerStoreUids.includes(storeUid)) notFound('매장을 찾을 수 없습니다.', instance)
  return store
}

function ownedItem(storeUid: string, itemUid: string, instance: string): ItemResponse {
  ownedStore(storeUid, instance)
  const item = state.items.find((i) => i.itemUid === itemUid && i.storeUid === storeUid)
  if (!item) notFound('상품을 찾을 수 없습니다.', instance)
  return item
}

function ownedOrder(orderId: number, instance: string): OrderResponse {
  sweep()
  const order = state.orders.find((o) => o.orderId === orderId)
  const item = order && state.items.find((i) => i.itemUid === order.itemUid)
  if (!order || !item || !state.ownerStoreUids.includes(item.storeUid)) notFound('예약을 찾을 수 없습니다.', instance)
  return order
}

export const mockOwner = {
  myStores(): Promise<StoreResponse[]> {
    return delay(state.stores.filter((s) => state.ownerStoreUids.includes(s.storeUid)))
  },
  createStore(request: CreateStoreRequest): Promise<StoreResponse> {
    if (!request.name.trim() || request.name.length > 20) badRequest('매장 이름은 1~20자여야 합니다.', '/api/owner/stores')
    const store: StoreResponse = {
      storeUid: uid('st'),
      name: request.name,
      storeType: request.storeType,
      openAt: withSeconds(request.openAt),
      closeAt: withSeconds(request.closeAt),
      logoImgUrl: request.logoImgUrl || undefined,
      shutdown: false,
    }
    state.stores.push(store)
    state.ownerStoreUids.push(store.storeUid)
    persist()
    return delay(store)
  },
  updateHours(storeUid: string, request: UpdateStoreHoursRequest): Promise<StoreResponse> {
    const store = ownedStore(storeUid, `/api/owner/stores/${storeUid}`)
    store.openAt = withSeconds(request.openAt)
    store.closeAt = withSeconds(request.closeAt)
    persist()
    return delay(store)
  },
  shutdown(storeUid: string): Promise<StoreResponse> {
    const instance = `/api/owner/stores/${storeUid}/shutdown`
    sweep()
    const store = ownedStore(storeUid, instance)
    const itemUids = new Set(state.items.filter((i) => i.storeUid === storeUid).map((i) => i.itemUid))
    if (state.orders.some((o) => itemUids.has(o.itemUid) && activeStates.includes(o.state))) {
      conflict('진행 중인 예약이 있어 영업을 종료할 수 없습니다.', instance)
    }
    store.shutdown = true
    persist()
    return delay(store)
  },
  createItem(storeUid: string, request: CreateItemRequest): Promise<ItemResponse> {
    const instance = `/api/owner/stores/${storeUid}/items`
    ownedStore(storeUid, instance)
    if (request.salePrice > request.originalPrice) badRequest('할인가는 정가보다 클 수 없습니다.', instance)
    if (request.initialQuantity < 1 || request.initialQuantity > 10000) badRequest('수량은 1~10000 사이여야 합니다.', instance)
    const lastOrderTime = withSeconds(request.lastOrderTime)
    if (new Date(lastOrderTime).getTime() <= Date.now()) badRequest('예약 마감 시각은 현재 이후여야 합니다.', instance)
    const item: ItemResponse = {
      itemUid: uid('it'),
      storeUid,
      name: request.name,
      originalPrice: request.originalPrice,
      salePrice: request.salePrice,
      initialQuantity: request.initialQuantity,
      remainingQuantity: request.initialQuantity,
      lastOrderTime,
      itemImgUrl: request.itemImgUrl || undefined,
    }
    state.items.push(item)
    persist()
    return delay(item)
  },
  updateQuantity(storeUid: string, itemUid: string, request: UpdateItemQuantityRequest): Promise<ItemResponse> {
    const instance = `/api/owner/stores/${storeUid}/items/${itemUid}/quantity`
    const item = ownedItem(storeUid, itemUid, instance)
    if (request.initialQuantity < 1 || request.initialQuantity > 10000) badRequest('수량은 1~10000 사이여야 합니다.', instance)
    const reserved = item.initialQuantity - item.remainingQuantity
    if (request.initialQuantity < reserved) {
      conflict(`이미 예약된 수량(${reserved}개)보다 적게 줄일 수 없습니다.`, instance)
    }
    item.initialQuantity = request.initialQuantity
    item.remainingQuantity = request.initialQuantity - reserved
    persist()
    return delay(item)
  },
  closeItem(storeUid: string, itemUid: string): Promise<ItemResponse> {
    const item = ownedItem(storeUid, itemUid, `/api/owner/stores/${storeUid}/items/${itemUid}/close`)
    if (new Date(item.lastOrderTime).getTime() > Date.now()) {
      item.lastOrderTime = toLocalIso(new Date())
      persist()
    }
    return delay(item)
  },
  itemOrders(storeUid: string, itemUid: string, filter?: OrderState): Promise<OrderResponse[]> {
    ownedItem(storeUid, itemUid, `/api/owner/stores/${storeUid}/items/${itemUid}/orders`)
    sweep()
    return delay(
      state.orders
        .filter((o) => o.itemUid === itemUid && (!filter || o.state === filter))
        .sort((a, b) => b.orderId - a.orderId)
        .map(ownerView),
    )
  },
  approve(orderId: number): Promise<OrderResponse> {
    const instance = `/api/owner/orders/${orderId}/approve`
    const order = ownedOrder(orderId, instance)
    transition(order, ['PENDING'], 'APPROVED', '승인', instance)
    persist()
    return delay(ownerView(order))
  },
  ready(orderId: number): Promise<OrderResponse> {
    const instance = `/api/owner/orders/${orderId}/ready`
    const order = ownedOrder(orderId, instance)
    transition(order, ['APPROVED'], 'READY_FOR_PICKUP', '준비 완료 처리', instance)
    order.readyAt = toLocalIso(new Date())
    order.pickupCode = newPickupCode()
    persist()
    return delay(ownerView(order))
  },
  reject(orderId: number): Promise<OrderResponse> {
    const instance = `/api/owner/orders/${orderId}/reject`
    const order = ownedOrder(orderId, instance)
    transition(order, ['PENDING'], 'CANCELED', '거절', instance)
    restoreStock(order)
    persist()
    return delay(ownerView(order))
  },
  pickup(orderId: number, request: PickupRequest): Promise<OrderResponse> {
    const instance = `/api/owner/orders/${orderId}/pickup`
    const order = ownedOrder(orderId, instance)
    if (!/^\d{6}$/.test(request.pickupCode)) badRequest('픽업 코드는 6자리 숫자입니다.', instance)
    if (order.state === 'READY_FOR_PICKUP' && order.pickupCode !== request.pickupCode) {
      badRequest('픽업 코드가 일치하지 않습니다.', instance)
    }
    transition(order, ['READY_FOR_PICKUP'], 'PICKED_UP', '픽업 완료 처리', instance)
    delete order.pickupCode
    persist()
    return delay(ownerView(order))
  },
}
