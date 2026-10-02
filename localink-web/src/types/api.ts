export interface Result<T> {
  code: number
  message: string
  data: T
}

export interface Page<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

export interface UserDTO {
  id: string
  phone: string
  nickName: string
  icon: string
  level: number
}

export interface ShopVO {
  id: string
  name: string
  typeId: string
  images: string
  area: string
  address: string
  longitude: number
  latitude: number
  avgPrice: number
  sold: number
  comments: number
  score: number
  openHours: string
}

export interface ShopTypeVO {
  id: string
  name: string
  icon: string
  sort: number
}

export interface VoucherVO {
  id: string
  shopId: string
  title: string
  subTitle: string
  rules: string
  payValue: number
  actualValue: number
  type: number
  status: number
  createTime: string
}

export interface SeckillVoucherVO {
  voucherId: string
  shopId: string
  title: string
  subTitle: string
  rules: string
  payValue: number
  actualValue: number
  status: number
  stock: number
  minLevel: number
  beginTime: string
  endTime: string
}

export interface VoucherOrderVO {
  id: string
  userId: string
  voucherId: string
  voucherType: number
  status: number
  title: string | null
  createTime: string
  closeTime: string | null
}

export interface SubscribeStatsVO {
  queueSize: number
  subscribedCount: number
  grantedCount: number
  noticeSent: boolean
  title: string
  beginTime: string | null
  endTime: string | null
}

export interface PageVO<T> {
  total: number
  records: T[]
}

export interface ScrollVO<T> {
  records: T[]
  nextCursor: number | null
}

export interface PostVO {
  id: string
  userId: string
  nickName: string
  shopId: string | null
  title: string
  images: string[]
  content: string
  liked: number
  meLiked: boolean | null
  comments: number
  viewed: number
  auditStatus?: number
  createTime: string
}

export interface CommentVO {
  id: string
  postId: string
  userId: string
  nickName: string
  parentId: string
  replyId: string
  replyNickName: string | null
  content: string
  liked: number
  createTime: string
  children: CommentVO[] | null
}

export interface UserBriefVO {
  userId: string
  nickName: string
  icon: string
}

export interface SignVO {
  signedToday: boolean
  continuousDays: number
  monthDays: number
}

export interface PostSearchVO {
  id: string
  title: string
  titleHighlight: string | null
  contentHighlight: string | null
  nickName: string
  shopId: string | null
  liked: number
  createTime: string
}

export interface ShopFacetVO {
  shopId: string
  shopName: string
  count: number
}

export interface SearchVO {
  records: PostSearchVO[]
  nextSearchAfter: string | null
  shopFacets: ShopFacetVO[]
}

export interface TopBuyerRow {
  userId: string
  nickName: string
  count: number
}

export interface ReconcileLogVO {
  id: string
  orderId: string
  userId: string
  voucherId: string
  traceId: string
  logType: number
  businessType: number
  reconciliationStatus: number
  beforeQty: number
  changeQty: number
  afterQty: number
  detail: string | null
  createTime: string
}

export interface RollbackFailureVO {
  id: string
  voucherId: string
  userId: string
  orderId: string | null
  traceId: string
  resultCode: string | null
  retryAttempts: number
  source: string
  detail: string | null
  createTime: string
}
