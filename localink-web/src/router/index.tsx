import { createBrowserRouter, Navigate } from 'react-router-dom'
import AppLayout from '../components/AppLayout'
import ShopListPage from '../pages/ShopListPage'
import ShopDetailPage from '../pages/ShopDetailPage'
import SeckillDetailPage from '../pages/SeckillDetailPage'
import MyOrdersPage from '../pages/MyOrdersPage'
import CommunityPage from '../pages/CommunityPage'
import PostDetailPage from '../pages/PostDetailPage'
import SearchPage from '../pages/SearchPage'
import LoginPage from '../pages/LoginPage'
import AdminLayout from '../pages/admin/AdminLayout'
import AdminLoginPage from '../pages/admin/AdminLoginPage'
import AdminShopsPage from '../pages/admin/AdminShopsPage'
import AdminVouchersPage from '../pages/admin/AdminVouchersPage'
import AdminOrdersPage from '../pages/admin/AdminOrdersPage'
import AdminSubscribePage from '../pages/admin/AdminSubscribePage'
import AdminAuditPage from '../pages/admin/AdminAuditPage'
import AdminTopBuyersPage from '../pages/admin/AdminTopBuyersPage'
import AdminReconcilePage from '../pages/admin/AdminReconcilePage'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <ShopListPage /> },
      { path: 'shop/:id', element: <ShopDetailPage /> },
      { path: 'seckill/:id', element: <SeckillDetailPage /> },
      { path: 'orders', element: <MyOrdersPage /> },
      { path: 'community', element: <CommunityPage /> },
      { path: 'post/:id', element: <PostDetailPage /> },
      { path: 'search', element: <SearchPage /> },
    ],
  },
  { path: '/login', element: <LoginPage /> },
  {
    path: '/admin',
    element: <AdminLayout />,
    children: [
      { index: true, element: <Navigate to="/admin/shops" replace /> },
      { path: 'shops', element: <AdminShopsPage /> },
      { path: 'vouchers', element: <AdminVouchersPage /> },
      { path: 'orders', element: <AdminOrdersPage /> },
      { path: 'subscribe', element: <AdminSubscribePage /> },
      { path: 'audit', element: <AdminAuditPage /> },
      { path: 'top-buyers', element: <AdminTopBuyersPage /> },
      { path: 'reconcile', element: <AdminReconcilePage /> },
    ],
  },
  { path: '/admin/login', element: <AdminLoginPage /> },
  { path: '*', element: <Navigate to="/" replace /> },
])
