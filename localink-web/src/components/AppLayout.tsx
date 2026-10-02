import { Layout, Button, Space, Typography } from 'antd'
import { Outlet, useNavigate } from 'react-router-dom'
import { useAuthStore } from '../stores/authStore'
import { logout } from '../api/user'

const { Header, Content, Footer } = Layout

export default function AppLayout() {
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const clear = useAuthStore((s) => s.clear)

  const handleLogout = async () => {
    try {
      await logout()
    } finally {
      clear()
    }
    navigate('/login')
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <Space size="large">
          <Typography.Title level={4} style={{ color: '#fff', margin: 0, cursor: 'pointer' }} onClick={() => navigate('/')}>
            Localink
          </Typography.Title>
          <Button type="link" style={{ color: '#fff', padding: 0 }} onClick={() => navigate('/')}>
            商户
          </Button>
          <Button type="link" style={{ color: '#fff', padding: 0 }} onClick={() => navigate('/community')}>
            社区
          </Button>
          <Button type="link" style={{ color: '#fff', padding: 0 }} onClick={() => navigate('/search')}>
            搜索
          </Button>
          <Button type="link" style={{ color: '#fff', padding: 0 }} onClick={() => navigate('/orders')}>
            我的订单
          </Button>
        </Space>
        {user ? (
          <Space>
            <Typography.Text style={{ color: '#fff' }}>{user.nickName || user.phone}</Typography.Text>
            <Button size="small" ghost onClick={handleLogout}>
              退出
            </Button>
          </Space>
        ) : (
          <Button size="small" ghost onClick={() => navigate('/login')}>
            登录
          </Button>
        )}
      </Header>
      <Content style={{ padding: '24px 48px', maxWidth: 1200, margin: '0 auto', width: '100%' }}>
        <Outlet />
      </Content>
      <Footer style={{ textAlign: 'center', color: '#999' }}>Localink — 本地生活社区（后端能力演示）</Footer>
    </Layout>
  )
}
