import dayjs from 'dayjs'
import type { SeckillVoucherVO } from '../types/api'

export type Phase = 'before' | 'running' | 'ended' | 'off'

export function phaseOf(seckill: SeckillVoucherVO, now: number): Phase {
  if (seckill.status !== 1) {
    return 'off'
  }
  const begin = dayjs(seckill.beginTime, 'YYYY-MM-DD HH:mm:ss').valueOf()
  const end = dayjs(seckill.endTime, 'YYYY-MM-DD HH:mm:ss').valueOf()
  if (!Number.isFinite(begin) || !Number.isFinite(end)) {
    return 'off'
  }
  if (now < begin) {
    return 'before'
  }
  if (now > end) {
    return 'ended'
  }
  return 'running'
}

export const PHASE_TAG: Record<Phase, { color: string; text: string }> = {
  before: { color: 'blue', text: '未开始' },
  running: { color: 'red', text: '进行中' },
  ended: { color: 'default', text: '已结束' },
  off: { color: 'default', text: '已下架' },
}
