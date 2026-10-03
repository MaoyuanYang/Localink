import axios from 'axios'
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('antd', () => ({ message: { error: vi.fn() } }))

// 用自定义 axios adapter 拦截请求：既能量产请求配置（token 注入断言），
// 也能伪造响应（Result 解包 / 40002 分流 / 网络异常），不依赖真实后端。
// 注意：request.ts 在 import 时即 axios.create() 并快照 defaults，
// 因此必须先装 adapter、再动态加载被测模块。
const adapter = vi.fn()
axios.defaults.adapter = adapter as unknown as AxiosAdapter

const { get } = await import('../request')
const { message } = await import('antd')
const { useAuthStore } = await import('../../stores/authStore')

function respondWith(payload: unknown): Promise<AxiosResponse> {
  return Promise.resolve({
    data: payload,
    status: 200,
    statusText: 'OK',
    headers: {},
    config: {} as InternalAxiosRequestConfig,
  })
}

const originalLocation = window.location

beforeEach(() => {
  adapter.mockReset()
  // jsdom 禁止导航：以可写桩替换 window.location 供 40002 跳转断言
  delete (window as unknown as Record<string, unknown>).location
  ;(window as unknown as Record<string, unknown>).location = {
    href: 'http://localhost/',
    pathname: '/',
  }
  useAuthStore.getState().clear()
  vi.mocked(message.error).mockClear()
})

afterEach(() => {
  ;(window as unknown as Record<string, unknown>).location = originalLocation
})

describe('响应拦截器：Result 解包', () => {
  it('code=0 直接返回 data', async () => {
    adapter.mockResolvedValueOnce(respondWith({ code: 0, message: 'ok', data: { id: 7 } }))
    await expect(get('/api/x')).resolves.toEqual({ id: 7 })
  })

  it('非 0 业务码 reject 并弹错误提示', async () => {
    adapter.mockResolvedValueOnce(respondWith({ code: 10005, message: '每人限购一单', data: null }))
    await expect(get('/api/x')).rejects.toThrow('每人限购一单')
    expect(message.error).toHaveBeenCalledWith('每人限购一单')
  })

  it('网络层异常 reject 并提示网络异常', async () => {
    adapter.mockRejectedValueOnce(new Error('ECONNREFUSED'))
    await expect(get('/api/x')).rejects.toThrow('ECONNREFUSED')
    expect(message.error).toHaveBeenCalledWith('网络异常，请稍后重试')
  })
})

describe('响应拦截器：40002 未登录分流', () => {
  it('C 端页面跳 /login 并清空登录态', async () => {
    useAuthStore.getState().setToken('tok-x')
    ;(window as unknown as Record<string, unknown>).location = {
      href: 'http://localhost/',
      pathname: '/orders',
    }
    adapter.mockResolvedValueOnce(respondWith({ code: 40002, message: '未登录或登录已过期', data: null }))
    await expect(get('/api/order/page')).rejects.toThrow()
    expect(useAuthStore.getState().token).toBeNull()
    expect(window.location.href).toBe('/login')
  })

  it('admin 区页面跳 /admin/login', async () => {
    ;(window as unknown as Record<string, unknown>).location = {
      href: 'http://localhost/admin/orders',
      pathname: '/admin/orders',
    }
    adapter.mockResolvedValueOnce(respondWith({ code: 40002, message: '未登录或登录已过期', data: null }))
    await expect(get('/api/order/admin/page')).rejects.toThrow()
    expect(window.location.href).toBe('/admin/login')
  })

  it('已在登录页时不重复跳转', async () => {
    ;(window as unknown as Record<string, unknown>).location = {
      href: 'http://localhost/login',
      pathname: '/login',
    }
    adapter.mockResolvedValueOnce(respondWith({ code: 40002, message: '未登录或登录已过期', data: null }))
    await expect(get('/api/x')).rejects.toThrow()
    expect(window.location.href).toBe('http://localhost/login')
  })
})

describe('请求拦截器：token 注入', () => {
  it('登录后请求头携带原始 token（无 Bearer 前缀，与后端口径一致）', async () => {
    useAuthStore.getState().setToken('tok-raw-123')
    adapter.mockResolvedValueOnce(respondWith({ code: 0, message: 'ok', data: null }))
    await get('/api/user/me')
    const config = adapter.mock.calls[0][0] as InternalAxiosRequestConfig
    expect(config.headers?.Authorization).toBe('tok-raw-123')
  })

  it('未登录时不注入 Authorization 头', async () => {
    adapter.mockResolvedValueOnce(respondWith({ code: 0, message: 'ok', data: null }))
    await get('/api/shop/page')
    const config = adapter.mock.calls[0][0] as InternalAxiosRequestConfig
    expect(config.headers?.Authorization).toBeUndefined()
  })
})
