export const AUTH_BASE_URL: string = import.meta.env.VITE_AUTH_BASE_URL ?? 'http://localhost:8081'
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
export const USE_MOCK: boolean = (import.meta.env.VITE_USE_MOCK ?? 'true') !== 'false'
