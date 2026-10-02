import { get } from './request'
import type { TopBuyerRow } from '../types/api'

export function fetchTopBuyers(shopId: string, date?: string) {
  return get<TopBuyerRow[]>(`/api/shop/${shopId}/top-buyers`, { date })
}
