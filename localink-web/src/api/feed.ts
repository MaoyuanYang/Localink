import { get } from './request'
import type { ScrollVO, PostVO } from '../types/api'

export function fetchFeed(lastScore: number | null, size = 10) {
  return get<ScrollVO<PostVO>>('/api/feed', { lastScore: lastScore ?? undefined, size })
}
