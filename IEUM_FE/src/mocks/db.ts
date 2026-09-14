import { ApiError } from '../api/errors'
import type {
  CreateItemRequest,
  CreateOrderRequest,
  CreateStoreRequest,
  ItemResponse,
  OrderResponse,
  OrderState,
  StoreResponse,
} from '../types/api'
import { seedItems, seedOrders, seedOwnerStoreUids, seedStores, toLocalIso } from './seed'

const KEY = 'ieum.mock.v1'

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

function uid(prefix: string) {
  return `${prefix}-${Math.random().toString(36).slice(2, 10)}`
}

export function resetMock() {
  state = fresh()
  persist()
}

export const mockStores = {
  list(): Promise<StoreResponse[]> {
    return delay(state.stores)
  },
  get(storeUid: string): Promise<StoreResponse> {
    const store = state.stores.find((s) => s.storeUid === storeUid)
    if (!store) fail(404, 'Not Found', '매장을 찾을 수 없습니다.', `/api/stores/${storeUid}`)
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
    if (!item) fail(404, 'Not Found', '상품을 찾을 수 없습니다.', `/api/items/${itemUid}`)
    return delay(item)
  },
}

function transition(order: OrderResponse, expected: OrderState[], next: OrderState, action: string, instance: string) {
  if (!expected.includes(order.state)) {
    fail(409, 'Conflict', `${order.state} 상태에서는 ${action} 할 수 없습니다.`, instance)
  }
  order.state = next
  if (next === 'READY_FOR_PICKUP') order.readyAt = toLocalIso(new Date())
}

export const mockOrders = {
  mine(): Promise<OrderResponse[]> {
    return delay([...state.orders].sort((a, b) => b.orderId - a.orderId))
  },
  create(request: CreateOrderRequest, idempotencyKey: string): Promise<OrderResponse> {
    const existingId = state.idempotency[idempotencyKey]
    if (existingId !== undefined) {
      const existing = state.orders.find((o) => o.orderId === existingId)
      if (existing) return delay(existing)
    }
    const item = state.items.find((i) => i.itemUid === request.itemUid)
    if (!item) fail(404, 'Not Found', '상품을 찾을 수 없습니다.', '/api/orders')
    if (new Date(item.lastOrderTime) < new Date()) fail(409, 'Conflict', '예약 마감 시간이 지났습니다.', '/api/orders')
    const active = state.orders.find(
      (o) => o.itemUid === item.itemUid && ['PENDING', 'APPROVED', 'READY_FOR_PICKUP'].includes(o.state),
    )
    if (active) fail(409, 'Conflict', '이미 진행 중인 예약이 있는 상품입니다.', '/api/orders')
    if (item.remainingQuantity < request.quantity) {
      fail(409, 'Conflict', `재고가 부족합니다. 남은 수량: ${item.remainingQuantity}`, '/api/orders')
    }
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
    return delay(order)
  },
  cancel(orderId: number): Promise<OrderResponse> {
    const order = state.orders.find((o) => o.orderId === orderId)
    if (!order) fail(404, 'Not Found', '예약을 찾을 수 없습니다.', `/api/orders/${orderId}/cancel`)
    transition(order, ['PENDING', 'APPROVED', 'READY_FOR_PICKUP'], 'CANCELED', '취소', `/api/orders/${orderId}/cancel`)
    const item = state.items.find((i) => i.itemUid === order.itemUid)
    if (item) item.remainingQuantity = Math.min(item.initialQuantity, item.remainingQuantity + order.quantity)
    persist()
    return delay(order)
  },
}

function ownerItemUids() {
  return new Set(state.items.filter((i) => state.ownerStoreUids.includes(i.storeUid)).map((i) => i.itemUid))
}

function ownerOrder(orderId: number, instance: string): OrderResponse {
  const order = state.orders.find((o) => o.orderId === orderId)
  if (!order) fail(404, 'Not Found', '예약을 찾을 수 없습니다.', instance)
  if (!ownerItemUids().has(order.itemUid)) fail(403, 'Forbidden', '접근 권한이 없습니다.', instance)
  return order
}

export const mockOwner = {
  myStores(): Promise<StoreResponse[]> {
    return delay(state.stores.filter((s) => state.ownerStoreUids.includes(s.storeUid)))
  },
  createStore(request: CreateStoreRequest): Promise<StoreResponse> {
    const store: StoreResponse = {
      storeUid: uid('st'),
      name: request.name,
      storeType: request.storeType,
      openAt: request.openAt.length === 5 ? `${request.openAt}:00` : request.openAt,
      closeAt: request.closeAt.length === 5 ? `${request.closeAt}:00` : request.closeAt,
      logoImgUrl: request.logoImgUrl || undefined,
      shutdown: false,
    }
    state.stores.push(store)
    state.ownerStoreUids.push(store.storeUid)
    persist()
    return delay(store)
  },
  createItem(storeUid: string, request: CreateItemRequest): Promise<ItemResponse> {
    if (!state.ownerStoreUids.includes(storeUid)) {
      fail(403, 'Forbidden', '접근 권한이 없습니다.', `/api/owner/stores/${storeUid}/items`)
    }
    const item: ItemResponse = {
      itemUid: uid('it'),
      storeUid,
      name: request.name,
      originalPrice: request.originalPrice,
      salePrice: request.salePrice,
      initialQuantity: request.initialQuantity,
      remainingQuantity: request.initialQuantity,
      lastOrderTime: request.lastOrderTime.length === 16 ? `${request.lastOrderTime}:00` : request.lastOrderTime,
      itemImgUrl: request.itemImgUrl || undefined,
    }
    state.items.push(item)
    persist()
    return delay(item)
  },
  orders(): Promise<OrderResponse[]> {
    const mine = ownerItemUids()
    return delay(state.orders.filter((o) => mine.has(o.itemUid)).sort((a, b) => b.orderId - a.orderId))
  },
  approve(orderId: number): Promise<OrderResponse> {
    const instance = `/api/owner/orders/${orderId}/approve`
    const order = ownerOrder(orderId, instance)
    transition(order, ['PENDING'], 'APPROVED', '승인', instance)
    persist()
    return delay(order)
  },
  ready(orderId: number): Promise<OrderResponse> {
    const instance = `/api/owner/orders/${orderId}/ready`
    const order = ownerOrder(orderId, instance)
    transition(order, ['APPROVED'], 'READY_FOR_PICKUP', '준비 완료 처리', instance)
    persist()
    return delay(order)
  },
  pickup(orderId: number): Promise<OrderResponse> {
    const instance = `/api/owner/orders/${orderId}/pickup`
    const order = ownerOrder(orderId, instance)
    transition(order, ['READY_FOR_PICKUP'], 'PICKED_UP', '픽업 완료 처리', instance)
    persist()
    return delay(order)
  },
}
