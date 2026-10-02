import { Layout, Menu, Button, Space, Typography } from 'antd'
import { Outlet, useLocation, useNavigate, Navigate } from 'react-router-dom'
import { useAuthStore } from '../../stores/authStore'
import { logout } from '../../api/user'

const { Sider, Header, Content } = Layout

const MENU_ITEMS = [
  { key: '/admin/shops', label: '商户管理' },
  { key: '/admin/vouchers', label: '券与活动' },
  { key: '/admin/orders', label: '订单监控' },
  { key: '/admin/subscribe', label: '订阅提醒' },
  { key: '/admin/audit', label: '审核队列' },
  { key: '/admin/top-buyers', label: 'Top 买家' },
  { key: '/admin/reconcile', label: '对账看板' },
]

export default function AdminLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const token = useAuthStore((s) => s.token)
  const user = useAuthStore((s) => s.user)
  const clear = useAuthStore((s) => s.clear)

  if (!token) {
    return <Navigate to="/admin/login" replace />
  }

  const handleLogout = async () => {
    try {
      await logout()
    } finally {
      clear()
    }
    navigate('/admin/login')
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="dark" width={200}>
        <div style={{ color: '#fff', padding: '20px 24px', fontSize: 16, fontWeight: 600 }}>
          Localink 运营后台
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={MENU_ITEMS}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            padding: '0 24px',
            borderBottom: '1px solid #f0f0f0',
          }}
        >
          <Button type="link" onClick={() => navigate('/')} style={{ padding: 0 }}>
            ← 返回 C 端
          </Button>
          <Space>
            <Typography.Text type="secondary">{user?.nickName || user?.phone}</Typography.Text>
            <Button size="small" onClick={handleLogout}>
              退出
            </Button>
          </Space>
        </Header>
        <Content style={{ padding: 24, background: '#f5f5f5' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
