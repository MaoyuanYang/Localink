import axios from 'axios'
import { message } from 'antd'
import type { AxiosRequestConfig } from 'axios'
import type { Result } from '../types/api'
import { useAuthStore } from '../stores/authStore'

const http = axios.create({ timeout: 10000 })

http.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token
  if (token) {
    config.headers.Authorization = token
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const result = response.data as Result<unknown>
    if (result.code === 0) {
      return result.data as never
    }
    if (result.code === 40002) {
      useAuthStore.getState().clear()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    } else {
      message.error(result.message)
    }
    return Promise.reject(new Error(result.message))
  },
  (error) => {
    message.error('网络异常，请稍后重试')
    return Promise.reject(error)
  },
)

export async function get<T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig): Promise<T> {
  return (await http.get(url, { params, ...config })) as unknown as T
}

export async function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return (await http.post(url, data, config)) as unknown as T
}

export async function del<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return (await http.delete(url, config)) as unknown as T
}
