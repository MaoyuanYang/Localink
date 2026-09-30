import { useNavigate } from 'react-router-dom'
import LoginForm from '../../components/LoginForm'

export default function AdminLoginPage() {
  const navigate = useNavigate()
  return <LoginForm title="运营后台登录" onSuccess={() => navigate('/admin/shops')} />
}
