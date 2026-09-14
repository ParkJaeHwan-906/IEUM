import { USE_MOCK } from './config'
import { apiRequest } from './http'
import { mockOrders } from '../mocks/db'
import type { CreateOrderRequest, OrderResponse } from '../types/api'

export function myOrders(): Promise<OrderResponse[]> {
  if (USE_MOCK) return mockOrders.mine()
  return apiRequest<OrderResponse[]>('/api/orders/me')
}

export function createOrder(request: CreateOrderRequest, idempotencyKey: string): Promise<OrderResponse> {
  if (USE_MOCK) return mockOrders.create(request, idempotencyKey)
  return apiRequest<OrderResponse>('/api/orders', {
    method: 'POST',
    body: request,
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function cancelOrder(orderId: number): Promise<OrderResponse> {
  if (USE_MOCK) return mockOrders.cancel(orderId)
  return apiRequest<OrderResponse>(`/api/orders/${orderId}/cancel`, { method: 'POST' })
}

export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}
