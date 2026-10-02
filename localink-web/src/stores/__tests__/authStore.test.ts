import { beforeEach, describe, expect, it } from 'vitest'
import { useAuthStore } from '../authStore'

describe('authStore 登录态', () => {
  beforeEach(() => {
    localStorage.clear()
    useAuthStore.setState({ token: null, user: null })
  })

  it('初始态未登录', () => {
    expect(useAuthStore.getState().token).toBeNull()
    expect(useAuthStore.getState().user).toBeNull()
  })

  it('setToken / setUser 更新登录态', () => {
    useAuthStore.getState().setToken('tok-abc')
    const user = { id: '1', phone: '13900000001', nickName: 'u', icon: '', level: 1 }
    useAuthStore.getState().setUser(user)
    const s = useAuthStore.getState()
    expect(s.token).toBe('tok-abc')
    expect(s.user).toEqual(user)
  })

  it('clear 清空登录态', () => {
    useAuthStore.getState().setToken('tok-abc')
    useAuthStore.getState().setUser({ id: '1', phone: '13900000001', nickName: 'u', icon: '', level: 1 })
    useAuthStore.getState().clear()
    expect(useAuthStore.getState().token).toBeNull()
    expect(useAuthStore.getState().user).toBeNull()
  })

  it('persist 中间件把 token 写入 localStorage(localink-auth)', () => {
    useAuthStore.getState().setToken('tok-persist')
    const raw = localStorage.getItem('localink-auth')
    expect(raw).toBeTruthy()
    const parsed = JSON.parse(raw as string) as { state: { token: string | null } }
    expect(parsed.state.token).toBe('tok-persist')
  })
})
