import { get, post, del } from './request'
import type { UserDTO } from '../types/api'

export function sendSmsCode(phone: string) {
  return post<void>('/api/sms/code', { phone })
}

export function devQuerySmsCode(phone: string) {
  return get<string>('/api/sms/code/dev', { phone })
}

export function login(phone: string, code: string) {
  return post<string>('/api/user/login', { phone, code })
}

export function fetchMe() {
  return get<UserDTO>('/api/user/me')
}

export function logout() {
  return del<void>('/api/user/logout')
}
