import { get } from './request'
import type { SearchVO } from '../types/api'

export function searchPosts(params: {
  keyword: string
  shopId?: string
  sort?: 'relevance' | 'time'
  searchAfter?: string
  size?: number
}) {
  return get<SearchVO>('/api/search/post', {
    keyword: params.keyword,
    shopId: params.shopId,
    sort: params.sort,
    searchAfter: params.searchAfter,
    size: params.size,
  })
}
