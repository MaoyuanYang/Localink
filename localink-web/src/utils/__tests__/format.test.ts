import { describe, expect, it } from 'vitest'
import { fenToYuan, firstImage, scoreOf } from '../format'

describe('fenToYuan 分转元', () => {
  it('常规金额两位小数', () => {
    expect(fenToYuan(12345)).toBe('123.45')
  })

  it('零元', () => {
    expect(fenToYuan(0)).toBe('0.00')
  })

  it('不足一元补零', () => {
    expect(fenToYuan(5)).toBe('0.05')
  })
})

describe('scoreOf 评分展示', () => {
  it('整数评分一位小数', () => {
    expect(scoreOf(45)).toBe('4.5')
  })

  it('非整十分四舍五入到一位', () => {
    expect(scoreOf(46)).toBe('4.6')
    expect(scoreOf(44)).toBe('4.4')
  })
})

describe('firstImage 逗号串取首图', () => {
  it('多图取第一张', () => {
    expect(firstImage('/upload/a.jpg,/upload/b.jpg')).toBe('/upload/a.jpg')
  })

  it('空串返回 undefined（图片区不渲染）', () => {
    expect(firstImage('')).toBeUndefined()
  })

  it('undefined 容错', () => {
    expect(firstImage(undefined as unknown as string)).toBeUndefined()
  })

  it('前段为空/空白时跳过取有效项', () => {
    expect(firstImage(' , /upload/x.jpg ')).toBe('/upload/x.jpg')
  })
})
