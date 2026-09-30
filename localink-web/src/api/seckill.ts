import { get, post, put, del } from './request'
import type { SeckillVoucherVO, SubscribeStatsVO } from '../types/api'

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

export function createSeckillVoucher(data: Record<string, unknown>) {
  return post<string>('/api/seckill-voucher', data)
}

export function updateSeckillVoucher(data: Record<string, unknown>) {
  return put<void>('/api/seckill-voucher', data)
}

export function deleteSeckillVoucher(voucherId: string) {
  return del<void>(`/api/seckill-voucher/${voucherId}`)
}

export function fetchSubscribeStats(voucherId: string) {
  return get<SubscribeStatsVO>(`/api/seckill-voucher/${voucherId}/subscribe-stats`)
}
