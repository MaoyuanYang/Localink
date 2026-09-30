import { get } from './request'
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
