import { get, post, put, del } from './request'
import type { VoucherVO } from '../types/api'

export function listVouchers(shopId: string) {
  return get<VoucherVO[]>('/api/voucher/list', { shopId })
}

export function claimVoucher(id: string) {
  return post<string>(`/api/voucher/${id}/claim`)
}

export function createVoucher(data: Record<string, unknown>) {
  return post<string>('/api/voucher', data)
}

export function updateVoucher(data: Record<string, unknown>) {
  return put<void>('/api/voucher', data)
}

export function deleteVoucher(id: string) {
  return del<void>(`/api/voucher/${id}`)
}
