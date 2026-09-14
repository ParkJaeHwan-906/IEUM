export type UserType = 'CONSUMER' | 'BUSINESS_OWNER' | 'ADMIN'

export type StoreType = 'FOOD' | 'CAFE' | 'BAKERY' | 'BAR' | 'FAST_FOOD' | 'FOOD_INGREDIENTS'

export type OrderState =
  | 'PENDING'
  | 'APPROVED'
  | 'READY_FOR_PICKUP'
  | 'PICKED_UP'
  | 'CANCELED'
  | 'EXPIRED'

export interface SignupRequest {
  name: string
  tel: string
  email: string
  nickname: string
  password: string
  userType: Exclude<UserType, 'ADMIN'>
}

export interface SignupResponse {
  uid: string
}

export interface LoginRequest {
  email: string
  password: string
}

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  tokenType: 'Bearer'
  expiresIn: number
}

export interface ProblemDetail {
  type: string
  title: string
  status: number
  detail?: string
  instance?: string
}

export interface StoreResponse {
  storeUid: string
  name: string
  storeType: StoreType
  openAt: string
  closeAt: string
  logoImgUrl?: string
  shutdown: boolean
}

export interface ItemResponse {
  itemUid: string
  storeUid: string
  name: string
  originalPrice: number
  salePrice: number
  initialQuantity: number
  remainingQuantity: number
  lastOrderTime: string
  itemImgUrl?: string
}

export interface OrderResponse {
  orderId: number
  itemUid: string
  itemName: string
  quantity: number
  orderPrice: number
  state: OrderState
  readyAt?: string
  createdAt: string
}

export interface CreateOrderRequest {
  itemUid: string
  quantity: number
}

export interface CreateStoreRequest {
  name: string
  storeType: StoreType
  openAt: string
  closeAt: string
  logoImgUrl?: string
}

export interface CreateItemRequest {
  name: string
  originalPrice: number
  salePrice: number
  initialQuantity: number
  lastOrderTime: string
  itemImgUrl?: string
}
