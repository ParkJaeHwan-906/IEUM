import type { ItemResponse, OrderResponse, StoreResponse } from '../types/api'

function at(hoursFromNow: number, minute = 0): string {
  const d = new Date()
  d.setHours(d.getHours() + hoursFromNow, minute, 0, 0)
  return toLocalIso(d)
}

export function toLocalIso(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export const seedStores: StoreResponse[] = [
  { storeUid: 'st-bakery-haneul', name: '하늘빵집', storeType: 'BAKERY', openAt: '08:00:00', closeAt: '21:00:00', shutdown: false },
  { storeUid: 'st-cafe-moon', name: '달빛커피', storeType: 'CAFE', openAt: '09:00:00', closeAt: '22:00:00', shutdown: false },
  { storeUid: 'st-food-jinmi', name: '진미반찬', storeType: 'FOOD', openAt: '10:00:00', closeAt: '20:00:00', shutdown: false },
  { storeUid: 'st-ingr-market', name: '동네청과', storeType: 'FOOD_INGREDIENTS', openAt: '07:00:00', closeAt: '21:30:00', shutdown: false },
  { storeUid: 'st-fast-burger', name: '골목버거', storeType: 'FAST_FOOD', openAt: '11:00:00', closeAt: '23:00:00', shutdown: false },
  { storeUid: 'st-bar-sool', name: '술익는집', storeType: 'BAR', openAt: '17:00:00', closeAt: '02:00:00', shutdown: false },
  { storeUid: 'st-food-closed', name: '문닫은분식', storeType: 'FOOD', openAt: '10:00:00', closeAt: '20:00:00', shutdown: true },
]

export const seedItems: ItemResponse[] = [
  { itemUid: 'it-croissant', storeUid: 'st-bakery-haneul', name: '버터 크루아상 3개입', originalPrice: 9000, salePrice: 4500, initialQuantity: 10, remainingQuantity: 4, lastOrderTime: at(3) },
  { itemUid: 'it-sourdough', storeUid: 'st-bakery-haneul', name: '사워도우 식빵', originalPrice: 7500, salePrice: 3900, initialQuantity: 6, remainingQuantity: 6, lastOrderTime: at(2, 30) },
  { itemUid: 'it-scone', storeUid: 'st-bakery-haneul', name: '플레인 스콘 4개입', originalPrice: 8000, salePrice: 3500, initialQuantity: 8, remainingQuantity: 0, lastOrderTime: at(1) },
  { itemUid: 'it-coldbrew', storeUid: 'st-cafe-moon', name: '콜드브루 원액 500ml', originalPrice: 12000, salePrice: 6900, initialQuantity: 12, remainingQuantity: 9, lastOrderTime: at(5) },
  { itemUid: 'it-tiramisu', storeUid: 'st-cafe-moon', name: '티라미수 조각', originalPrice: 6500, salePrice: 3200, initialQuantity: 5, remainingQuantity: 2, lastOrderTime: at(2) },
  { itemUid: 'it-banchan-set', storeUid: 'st-food-jinmi', name: '오늘의 반찬 5종 세트', originalPrice: 15000, salePrice: 8000, initialQuantity: 15, remainingQuantity: 11, lastOrderTime: at(4) },
  { itemUid: 'it-kimchi', storeUid: 'st-food-jinmi', name: '갓 담근 배추김치 1kg', originalPrice: 13000, salePrice: 7500, initialQuantity: 20, remainingQuantity: 20, lastOrderTime: at(6) },
  { itemUid: 'it-japchae', storeUid: 'st-food-jinmi', name: '잡채 대용량', originalPrice: 9000, salePrice: 4000, initialQuantity: 7, remainingQuantity: 1, lastOrderTime: at(1, 30) },
  { itemUid: 'it-tomato', storeUid: 'st-ingr-market', name: '완숙 토마토 2kg', originalPrice: 11000, salePrice: 5500, initialQuantity: 30, remainingQuantity: 18, lastOrderTime: at(7) },
  { itemUid: 'it-banana', storeUid: 'st-ingr-market', name: '바나나 한 송이', originalPrice: 4500, salePrice: 1900, initialQuantity: 25, remainingQuantity: 25, lastOrderTime: at(8) },
  { itemUid: 'it-salad', storeUid: 'st-ingr-market', name: '샐러드 채소 믹스', originalPrice: 6000, salePrice: 2500, initialQuantity: 10, remainingQuantity: 3, lastOrderTime: at(2) },
  { itemUid: 'it-burger-set', storeUid: 'st-fast-burger', name: '치즈버거 세트', originalPrice: 8900, salePrice: 4900, initialQuantity: 20, remainingQuantity: 14, lastOrderTime: at(9) },
  { itemUid: 'it-fries', storeUid: 'st-fast-burger', name: '감자튀김 L', originalPrice: 3500, salePrice: 1500, initialQuantity: 30, remainingQuantity: 30, lastOrderTime: at(9) },
  { itemUid: 'it-anju', storeUid: 'st-bar-sool', name: '모둠 전 안주', originalPrice: 18000, salePrice: 9900, initialQuantity: 8, remainingQuantity: 5, lastOrderTime: at(10) },
  { itemUid: 'it-old', storeUid: 'st-bar-sool', name: '어제의 골뱅이무침', originalPrice: 15000, salePrice: 6000, initialQuantity: 5, remainingQuantity: 2, lastOrderTime: at(-2) },
]

export const seedOrders: OrderResponse[] = [
  { orderId: 1001, itemUid: 'it-croissant', itemName: '버터 크루아상 3개입', quantity: 2, orderPrice: 4500, state: 'READY_FOR_PICKUP', readyAt: at(0, -5), createdAt: at(-1) },
  { orderId: 1002, itemUid: 'it-banchan-set', itemName: '오늘의 반찬 5종 세트', quantity: 1, orderPrice: 8000, state: 'APPROVED', createdAt: at(-2) },
  { orderId: 1003, itemUid: 'it-tomato', itemName: '완숙 토마토 2kg', quantity: 3, orderPrice: 5500, state: 'PENDING', createdAt: at(0, -20) },
  { orderId: 1004, itemUid: 'it-tiramisu', itemName: '티라미수 조각', quantity: 1, orderPrice: 3200, state: 'PICKED_UP', readyAt: at(-26), createdAt: at(-27) },
  { orderId: 1005, itemUid: 'it-burger-set', itemName: '치즈버거 세트', quantity: 2, orderPrice: 4900, state: 'CANCELED', createdAt: at(-50) },
  { orderId: 1006, itemUid: 'it-scone', itemName: '플레인 스콘 4개입', quantity: 1, orderPrice: 3500, state: 'EXPIRED', readyAt: at(-75), createdAt: at(-76) },
]

export const seedOwnerStoreUids = ['st-bakery-haneul', 'st-cafe-moon']
