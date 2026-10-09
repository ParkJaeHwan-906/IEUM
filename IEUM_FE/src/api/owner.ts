import { USE_MOCK } from './config'
import { apiRequest } from './http'
import { mockOwner } from '../mocks/db'
import type {
  CreateItemRequest,
  CreateStoreRequest,
  ItemResponse,
  OrderResponse,
  OrderState,
  StoreResponse,
  UpdateStoreHoursRequest,
} from '../types/api'

export function myStores(): Promise<StoreResponse[]> {
  if (USE_MOCK) return mockOwner.myStores()
  return apiRequest<StoreResponse[]>('/api/owner/stores/me')
}

export function createStore(request: CreateStoreRequest): Promise<StoreResponse> {
  if (USE_MOCK) return mockOwner.createStore(request)
  return apiRequest<StoreResponse>('/api/owner/stores', { method: 'POST', body: request })
}

export function updateStoreHours(storeUid: string, request: UpdateStoreHoursRequest): Promise<StoreResponse> {
  if (USE_MOCK) return mockOwner.updateHours(storeUid, request)
  return apiRequest<StoreResponse>(`/api/owner/stores/${storeUid}`, { method: 'PATCH', body: request })
}

export function shutdownStore(storeUid: string): Promise<StoreResponse> {
  if (USE_MOCK) return mockOwner.shutdown(storeUid)
  return apiRequest<StoreResponse>(`/api/owner/stores/${storeUid}/shutdown`, { method: 'POST' })
}

export function createItem(storeUid: string, request: CreateItemRequest): Promise<ItemResponse> {
  if (USE_MOCK) return mockOwner.createItem(storeUid, request)
  return apiRequest<ItemResponse>(`/api/owner/stores/${storeUid}/items`, { method: 'POST', body: request })
}

export function updateItemQuantity(storeUid: string, itemUid: string, initialQuantity: number): Promise<ItemResponse> {
  if (USE_MOCK) return mockOwner.updateQuantity(storeUid, itemUid, { initialQuantity })
  return apiRequest<ItemResponse>(`/api/owner/stores/${storeUid}/items/${itemUid}/quantity`, {
    method: 'PATCH',
    body: { initialQuantity },
  })
}

export function closeItem(storeUid: string, itemUid: string): Promise<ItemResponse> {
  if (USE_MOCK) return mockOwner.closeItem(storeUid, itemUid)
  return apiRequest<ItemResponse>(`/api/owner/stores/${storeUid}/items/${itemUid}/close`, { method: 'POST' })
}

export function itemOrders(storeUid: string, itemUid: string, state?: OrderState): Promise<OrderResponse[]> {
  if (USE_MOCK) return mockOwner.itemOrders(storeUid, itemUid, state)
  const query = state ? `?state=${state}` : ''
  return apiRequest<OrderResponse[]>(`/api/owner/stores/${storeUid}/items/${itemUid}/orders${query}`)
}

export function approveOrder(orderId: number): Promise<OrderResponse> {
  if (USE_MOCK) return mockOwner.approve(orderId)
  return apiRequest<OrderResponse>(`/api/owner/orders/${orderId}/approve`, { method: 'POST' })
}

export function readyOrder(orderId: number): Promise<OrderResponse> {
  if (USE_MOCK) return mockOwner.ready(orderId)
  return apiRequest<OrderResponse>(`/api/owner/orders/${orderId}/ready`, { method: 'POST' })
}

export function rejectOrder(orderId: number): Promise<OrderResponse> {
  if (USE_MOCK) return mockOwner.reject(orderId)
  return apiRequest<OrderResponse>(`/api/owner/orders/${orderId}/reject`, { method: 'POST' })
}

export function pickupOrder(orderId: number, pickupCode: string): Promise<OrderResponse> {
  if (USE_MOCK) return mockOwner.pickup(orderId, { pickupCode })
  return apiRequest<OrderResponse>(`/api/owner/orders/${orderId}/pickup`, { method: 'POST', body: { pickupCode } })
}
