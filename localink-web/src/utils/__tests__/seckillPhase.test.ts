import { describe, expect, it } from 'vitest'
import { phaseOf } from '../seckillPhase'
import type { SeckillVoucherVO } from '../../types/api'

function voucher(over: Partial<SeckillVoucherVO> = {}): SeckillVoucherVO {
  return {
    voucherId: '1',
    shopId: '1',
    title: 't',
    subTitle: '',
    rules: '',
    payValue: 100,
    actualValue: 200,
    status: 1,
    stock: 10,
    minLevel: 0,
    beginTime: '2026-06-01 10:00:00',
    endTime: '2026-06-01 11:00:00',
    ...over,
  }
}

const BEGIN = new Date('2026-06-01T10:00:00').getTime()
const END = new Date('2026-06-01T11:00:00').getTime()

describe('phaseOf 秒杀相位判定', () => {
  it('status != 1（下架）直接 off，与时间窗无关', () => {
    expect(phaseOf(voucher({ status: 0 }), BEGIN + 1000)).toBe('off')
  })

  it('开始前为 before', () => {
    expect(phaseOf(voucher(), BEGIN - 1)).toBe('before')
  })

  it('进行中为 running', () => {
    expect(phaseOf(voucher(), BEGIN + 1000)).toBe('running')
  })

  it('now == begin 属于 running（左边界含）', () => {
    expect(phaseOf(voucher(), BEGIN)).toBe('running')
  })

  it('now == end 仍属 running（右边界不含 ended）', () => {
    expect(phaseOf(voucher(), END)).toBe('running')
  })

  it('结束后为 ended', () => {
    expect(phaseOf(voucher(), END + 1)).toBe('ended')
  })

  it('非法时间串判定为 off（不抛异常）', () => {
    expect(phaseOf(voucher({ beginTime: 'garbage' }), BEGIN)).toBe('off')
    expect(phaseOf(voucher({ endTime: '' }), BEGIN)).toBe('off')
  })
})
