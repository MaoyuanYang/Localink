import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { UserDTO } from '../types/api'

interface AuthState {
  token: string | null
  user: UserDTO | null
  setToken: (token: string) => void
  setUser: (user: UserDTO) => void
  clear: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      setToken: (token) => set({ token }),
      setUser: (user) => set({ user }),
      clear: () => set({ token: null, user: null }),
    }),
    { name: 'localink-auth' },
  ),
)
