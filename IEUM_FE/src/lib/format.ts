import type { OrderState, StoreType } from '../types/api'

export function won(value: number) {
  return `${value.toLocaleString('ko-KR')}원`
}

export function discountRate(original: number, sale: number) {
  if (original <= 0 || sale >= original) return 0
  return Math.round(((original - sale) / original) * 100)
}

export function time(value: string) {
  return value.slice(0, 5)
}

export function dateTime(value: string) {
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function remaining(value: string) {
  const diff = new Date(value).getTime() - Date.now()
  if (Number.isNaN(diff)) return ''
  if (diff <= 0) return '마감'
  const minutes = Math.floor(diff / 60000)
  if (minutes < 60) return `${minutes}분 남음`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}시간 ${minutes % 60}분 남음`
  return `${Math.floor(hours / 24)}일 남음`
}

export const storeTypeLabel: Record<StoreType, string> = {
  FOOD: '음식점',
  CAFE: '카페',
  BAKERY: '베이커리',
  BAR: '주점',
  FAST_FOOD: '패스트푸드',
  FOOD_INGREDIENTS: '식재료',
}

export const storeTypeEmoji: Record<StoreType, string> = {
  FOOD: '🍱',
  CAFE: '☕',
  BAKERY: '🥐',
  BAR: '🍶',
  FAST_FOOD: '🍔',
  FOOD_INGREDIENTS: '🥬',
}

export const orderStateLabel: Record<OrderState, string> = {
  PENDING: '승인 대기',
  APPROVED: '준비 중',
  READY_FOR_PICKUP: '픽업 가능',
  PICKED_UP: '픽업 완료',
  CANCELED: '취소됨',
  EXPIRED: '만료됨',
}

export const activeStates: OrderState[] = ['PENDING', 'APPROVED', 'READY_FOR_PICKUP']
