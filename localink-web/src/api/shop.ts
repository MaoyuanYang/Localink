import { get, post, put, del } from './request'
import type { Page, ShopTypeVO, ShopVO } from '../types/api'

export function pageShops(typeId: string | null, page: number, size: number) {
  return get<Page<ShopVO>>('/api/shop/page', {
    typeId: typeId ?? undefined,
    page,
    size,
  })
}

export function fetchShop(id: string) {
  return get<ShopVO>(`/api/shop/${id}`)
}

export function fetchShopTypes() {
  return get<ShopTypeVO[]>('/api/shop-type/list')
}

export function createShop(data: Record<string, unknown>) {
  return post<string>('/api/shop', data)
}

export function updateShop(data: Record<string, unknown>) {
  return put<void>('/api/shop', data)
}

export function deleteShop(id: string) {
  return del<void>(`/api/shop/${id}`)
}

export function createShopType(data: Record<string, unknown>) {
  return post<string>('/api/shop-type', data)
}

export function updateShopType(data: Record<string, unknown>) {
  return put<void>('/api/shop-type', data)
}

export function deleteShopType(id: string) {
  return del<void>(`/api/shop-type/${id}`)
}
