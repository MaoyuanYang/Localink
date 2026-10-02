import { get, post, del } from './request'
import type { CommentVO, PageVO, PostVO } from '../types/api'

export function createPost(data: { title: string; content: string; images?: string; shopId?: string }) {
  return post<string>('/api/post', data)
}

export function deletePost(postId: string) {
  return del<void>(`/api/post/${postId}`)
}

export function fetchPost(postId: string) {
  return get<PostVO>(`/api/post/${postId}`)
}

export function pagePosts(page: number, size: number, shopId?: string) {
  return get<PageVO<PostVO>>('/api/post/page', { page, size, shopId })
}

export function adminPagePosts(auditStatus: number | null, page: number, size: number) {
  return get<PageVO<PostVO>>('/api/post/admin/page', { auditStatus: auditStatus ?? undefined, page, size })
}

export function likePost(postId: string) {
  return post<number>(`/api/post/${postId}/like`)
}

export function unlikePost(postId: string) {
  return del<number>(`/api/post/${postId}/like`)
}

export function fetchHotPosts(limit = 10) {
  return get<PostVO[]>('/api/post/hot', { limit })
}

export function createComment(data: { postId: string; content: string; parentId?: string; replyId?: string }) {
  return post<string>('/api/post/comment', data)
}

export function deleteComment(commentId: string) {
  return del<void>(`/api/post/comment/${commentId}`)
}

export function pageComments(postId: string, page: number, size: number) {
  return get<PageVO<CommentVO>>(`/api/post/${postId}/comment/page`, { page, size })
}

export function uploadImage(file: File) {
  const form = new FormData()
  form.append('file', file)
  return post<string>('/api/upload/image', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}
