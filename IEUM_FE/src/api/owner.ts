import { USE_MOCK } from './config'
import { apiRequest } from './http'
import { mockOwner } from '../mocks/db'
import type { CreateItemRequest, CreateStoreRequest, ItemResponse, OrderResponse, StoreResponse } from '../types/api'

export function myStores(): Promise<StoreResponse[]> {
  if (USE_MOCK) return mockOwner.myStores()
  return apiRequest<StoreResponse[]>('/api/owner/stores/me')
}

export function createStore(request: CreateStoreRequest): Promise<StoreResponse> {
  if (USE_MOCK) return mockOwner.createStore(request)
  return apiRequest<StoreResponse>('/api/owner/stores', { method: 'POST', body: request })
}

export function createItem(storeUid: string, request: CreateItemRequest): Promise<ItemResponse> {
  if (USE_MOCK) return mockOwner.createItem(storeUid, request)
  return apiRequest<ItemResponse>(`/api/owner/stores/${storeUid}/items`, { method: 'POST', body: request })
}

export function ownerOrders(): Promise<OrderResponse[]> {
  if (USE_MOCK) return mockOwner.orders()
  return apiRequest<OrderResponse[]>('/api/owner/orders')
}

export function approveOrder(orderId: number): Promise<OrderResponse> {
  if (USE_MOCK) return mockOwner.approve(orderId)
  return apiRequest<OrderResponse>(`/api/owner/orders/${orderId}/approve`, { method: 'POST' })
}

export function readyOrder(orderId: number): Promise<OrderResponse> {
  if (USE_MOCK) return mockOwner.ready(orderId)
  return apiRequest<OrderResponse>(`/api/owner/orders/${orderId}/ready`, { method: 'POST' })
}

export function pickupOrder(orderId: number): Promise<OrderResponse> {
  if (USE_MOCK) return mockOwner.pickup(orderId)
  return apiRequest<OrderResponse>(`/api/owner/orders/${orderId}/pickup`, { method: 'POST' })
}
