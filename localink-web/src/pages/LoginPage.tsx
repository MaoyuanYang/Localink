import { useNavigate } from 'react-router-dom'
import LoginForm from '../components/LoginForm'

export default function LoginPage() {
  const navigate = useNavigate()
  return <LoginForm title="登录 Localink" onSuccess={() => navigate('/')} />
}
