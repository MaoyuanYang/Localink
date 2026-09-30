import { get, post } from './request'
import type { SeckillVoucherVO } from '../types/api'

export function fetchSeckillVoucher(id: string) {
  return get<SeckillVoucherVO>(`/api/seckill-voucher/${id}`)
}

export function listSeckillVouchers(shopId: string) {
  return get<SeckillVoucherVO[]>('/api/seckill-voucher/list', { shopId })
}

export function applySeckillToken(voucherId: string) {
  return post<string>(`/api/seckill-voucher/${voucherId}/token`)
}

export function seckillOrder(voucherId: string, token: string) {
  return post<string>(`/api/seckill-voucher/${voucherId}/seckill`, undefined, { params: { token } })
}
