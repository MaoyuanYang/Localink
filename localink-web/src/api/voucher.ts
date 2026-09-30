import { get, post } from './request'
import type { VoucherVO } from '../types/api'

export function listVouchers(shopId: string) {
  return get<VoucherVO[]>('/api/voucher/list', { shopId })
}

export function claimVoucher(id: string) {
  return post<string>(`/api/voucher/${id}/claim`)
}
