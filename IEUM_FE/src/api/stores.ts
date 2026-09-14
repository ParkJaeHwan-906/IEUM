import { USE_MOCK } from './config'
import { apiRequest } from './http'
import { mockStores } from '../mocks/db'
import type { ItemResponse, StoreResponse } from '../types/api'

export function listStores(): Promise<StoreResponse[]> {
  if (USE_MOCK) return mockStores.list()
  return apiRequest<StoreResponse[]>('/api/stores', { auth: false })
}

export function getStore(storeUid: string): Promise<StoreResponse> {
  if (USE_MOCK) return mockStores.get(storeUid)
  return apiRequest<StoreResponse>(`/api/stores/${storeUid}`, { auth: false })
}

export function listStoreItems(storeUid: string): Promise<ItemResponse[]> {
  if (USE_MOCK) return mockStores.items(storeUid)
  return apiRequest<ItemResponse[]>(`/api/stores/${storeUid}/items`, { auth: false })
}

export async function listAllItems(): Promise<ItemResponse[]> {
  if (USE_MOCK) return mockStores.allItems()
  const stores = await listStores()
  const lists = await Promise.all(stores.filter((s) => !s.shutdown).map((s) => listStoreItems(s.storeUid)))
  return lists.flat()
}

export function getItem(itemUid: string): Promise<ItemResponse> {
  if (USE_MOCK) return mockStores.item(itemUid)
  return apiRequest<ItemResponse>(`/api/items/${itemUid}`, { auth: false })
}
