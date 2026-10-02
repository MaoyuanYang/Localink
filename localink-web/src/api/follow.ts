import { get, post, del } from './request'
import type { UserBriefVO } from '../types/api'

export function followUser(userId: string) {
  return post<void>(`/api/follow/${userId}`)
}

export function unfollowUser(userId: string) {
  return del<void>(`/api/follow/${userId}`)
}

export function fetchCommonFollows(userId: string) {
  return get<UserBriefVO[]>(`/api/follow/common/${userId}`)
}
