import { get } from './request'
import type { Page, VoucherOrderVO } from '../types/api'

export function pageMyOrders(page: number, size: number) {
  return get<Page<VoucherOrderVO>>('/api/order/page', { page, size })
}
