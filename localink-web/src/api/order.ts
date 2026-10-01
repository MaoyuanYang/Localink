import { get, post } from './request'
import type { Page, VoucherOrderVO } from '../types/api'

export function pageMyOrders(page: number, size: number) {
  return get<Page<VoucherOrderVO>>('/api/order/page', { page, size })
}

export function adminPageOrdersByVoucher(voucherId: string, page: number, size: number) {
  return get<Page<VoucherOrderVO>>('/api/order/admin/page', { voucherId, page, size })
}

export function adminCloseOrder(orderId: string) {
  return post<boolean>(`/api/order/admin/${orderId}/close`)
}
