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
