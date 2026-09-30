import { createBrowserRouter, Navigate } from 'react-router-dom'
import AppLayout from '../components/AppLayout'
import ShopListPage from '../pages/ShopListPage'
import ShopDetailPage from '../pages/ShopDetailPage'
import LoginPage from '../pages/LoginPage'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <ShopListPage /> },
      { path: 'shop/:id', element: <ShopDetailPage /> },
    ],
  },
  { path: '/login', element: <LoginPage /> },
  { path: '*', element: <Navigate to="/" replace /> },
])
