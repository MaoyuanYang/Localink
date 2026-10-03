import { App as AntdApp } from 'antd'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import LoginPage from '../LoginPage'

vi.mock('../../api/user', () => ({
  login: vi.fn(),
  sendSmsCode: vi.fn(),
  devQuerySmsCode: vi.fn(),
  fetchMe: vi.fn(),
}))

describe('LoginPage 渲染冒烟', () => {
  it('渲染登录标题与手机号/验证码表单', () => {
    render(
      <MemoryRouter>
        <AntdApp>
          <LoginPage />
        </AntdApp>
      </MemoryRouter>,
    )
    expect(screen.getByText('登录 Localink')).toBeInTheDocument()
    const inputs = document.querySelectorAll('input')
    expect(inputs.length).toBeGreaterThanOrEqual(2)
  })

  it('表单含获取验证码按钮（两步登录入口）', () => {
    render(
      <MemoryRouter>
        <AntdApp>
          <LoginPage />
        </AntdApp>
      </MemoryRouter>,
    )
    expect(screen.getByRole('button', { name: /获取验证码|发送验证码/ })).toBeInTheDocument()
  })
})
