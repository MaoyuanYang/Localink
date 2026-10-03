import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import ShopListPage from '../ShopListPage'
import type { ShopTypeVO, ShopVO, Page } from '../../types/api'

vi.mock('../../api/shop', () => ({
  fetchShopTypes: vi.fn().mockResolvedValue([
    { id: '1', name: '美食', icon: '', sort: 1 },
  ] satisfies ShopTypeVO[]),
  pageShops: vi.fn().mockResolvedValue({
    records: [
      {
        id: '101',
        name: '体检测试店铺A',
        typeId: '1',
        images: '',
        area: '海淀',
        address: '某路1号',
        longitude: 116.3,
        latitude: 39.9,
        avgPrice: 8800,
        sold: 12,
        comments: 34,
        score: 45,
        openHours: '10:00-22:00',
      },
    ],
    total: 1,
    size: 9,
    current: 1,
    pages: 1,
  } satisfies Page<ShopVO>),
}))

describe('ShopListPage 渲染冒烟', () => {
  it('加载并渲染店铺卡片（mock API 数据）', async () => {
    render(
      <MemoryRouter>
        <ShopListPage />
      </MemoryRouter>,
    )
    expect(await screen.findByText('体检测试店铺A')).toBeInTheDocument()
    // 分转元：avgPrice=8800 分 → ¥88.00
    expect(screen.getByText(/88\.00/)).toBeInTheDocument()
  })

  it('渲染类型筛选下拉（店铺类型来自独立接口）', async () => {
    render(
      <MemoryRouter>
        <ShopListPage />
      </MemoryRouter>,
    )
    await screen.findByText('体检测试店铺A')
    expect(document.querySelector('.ant-select')).toBeInTheDocument()
  })
})
