import { Button, Card, Form, Input, Typography, App } from 'antd'
import { useState } from 'react'
import { devQuerySmsCode, fetchMe, login, sendSmsCode } from '../api/user'
import { useAuthStore } from '../stores/authStore'

interface LoginFormProps {
  title: string
  onSuccess: () => void
}

interface LoginFormValues {
  phone: string
  code: string
}

export default function LoginForm({ title, onSuccess }: LoginFormProps) {
  const { message: messageApi } = App.useApp()
  const [form] = Form.useForm<LoginFormValues>()
  const [countdown, setCountdown] = useState(0)
  const [sending, setSending] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const setToken = useAuthStore((s) => s.setToken)
  const setUser = useAuthStore((s) => s.setUser)

  const startCountdown = () => {
    setCountdown(60)
    const timer = setInterval(() => {
      setCountdown((c) => {
        if (c <= 1) {
          clearInterval(timer)
          return 0
        }
        return c - 1
      })
    }, 1000)
  }

  const handleSend = async () => {
    const phone = form.getFieldValue('phone') as string
    if (!phone || !/^1[3-9]\d{9}$/.test(phone)) {
      messageApi.warning('请先输入正确的手机号')
      return
    }
    setSending(true)
    try {
      await sendSmsCode(phone)
      startCountdown()
      if (import.meta.env.DEV) {
        try {
          const code = await devQuerySmsCode(phone)
          form.setFieldValue('code', code)
          messageApi.success(`dev 接口已自动回填验证码：${code}`)
        } catch {
          messageApi.info(`dev 取码接口未开启，请手动执行 redis-cli GET sms:code:${phone}`)
        }
      }
    } catch {
      // 发码失败已被拦截器 toast
    } finally {
      setSending(false)
    }
  }

  const handleFinish = async (values: LoginFormValues) => {
    if (submitting) return
    setSubmitting(true)
    try {
    const token = await login(values.phone, values.code)
    setToken(token)
    const me = await fetchMe()
    setUser(me)
    messageApi.success(`欢迎，${me.nickName || me.phone}`)
    onSuccess()
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f5f5f5' }}>
      <Card style={{ width: 380 }}>
        <Typography.Title level={3} style={{ textAlign: 'center', marginBottom: 24 }}>
          {title}
        </Typography.Title>
        <Form form={form} layout="vertical" onFinish={handleFinish}>
          <Form.Item
            name="phone"
            label="手机号"
            rules={[{ required: true, pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号' }]}
          >
            <Input placeholder="11 位手机号" maxLength={11} />
          </Form.Item>
          <Form.Item name="code" label="验证码" rules={[{ required: true, message: '请输入验证码' }]}>
            <Input
              placeholder="6 位验证码"
              maxLength={6}
              addonAfter={
                <Button type="link" size="small" disabled={countdown > 0 || sending} onClick={handleSend} style={{ padding: 0 }}>
                  {countdown > 0 ? `${countdown}s` : sending ? '发送中' : '发送验证码'}
                </Button>
              }
            />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0 }}>
            <Button type="primary" htmlType="submit" block loading={submitting}>
              登录（未注册自动创建账号）
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}
