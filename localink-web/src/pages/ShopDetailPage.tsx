import { Button, Card, Col, Descriptions, Empty, Image, List, Row, Skeleton, Tag, Typography, App } from 'antd'
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { fetchShop } from '../api/shop'
import { claimVoucher, listVouchers } from '../api/voucher'
import type { ShopVO, VoucherVO } from '../types/api'
import { fenToYuan, scoreOf } from '../utils/format'

export default function ShopDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { message: messageApi } = App.useApp()
  const [shop, setShop] = useState<ShopVO | null>(null)
  const [vouchers, setVouchers] = useState<VoucherVO[]>([])
  const [loading, setLoading] = useState(true)
  const [claimingId, setClaimingId] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    setLoading(true)
    Promise.all([fetchShop(id), listVouchers(id)])
      .then(([s, v]) => {
        setShop(s)
        setVouchers(v.filter((item) => item.type === 1))
      })
      .finally(() => setLoading(false))
  }, [id])

  const handleClaim = async (voucher: VoucherVO) => {
    setClaimingId(voucher.id)
    try {
      const orderId = await claimVoucher(voucher.id)
      messageApi.success(`领取成功，订单号 ${orderId}`)
    } catch {
      // 未登录 40002 由拦截器跳登录；其余失败已 toast
    } finally {
      setClaimingId(null)
    }
  }

  if (loading) {
    return <Skeleton active paragraph={{ rows: 10 }} />
  }
  if (!shop) {
    return <Empty description="商户不存在" />
  }

  const images = shop.images?.split(',').map((s) => s.trim()).filter(Boolean)

  return (
    <div>
      <Row gutter={24}>
        <Col span={14}>
          {images && images.length > 0 ? (
            <Image.PreviewGroup>
              <Row gutter={[12, 12]}>
                {images.map((img) => (
                  <Col key={img} span={12}>
                    <Image
                      src={img}
                      alt={shop.name}
                      height={200}
                      width="100%"
                      style={{ objectFit: 'cover', borderRadius: 8 }}
                      fallback="/placeholder-shop.svg"
                    />
                  </Col>
                ))}
              </Row>
            </Image.PreviewGroup>
          ) : (
            <Image src="/placeholder-shop.svg" alt={shop.name} height={200} width="100%" style={{ objectFit: 'cover', borderRadius: 8 }} />
          )}
        </Col>
        <Col span={10}>
          <Typography.Title level={3}>{shop.name}</Typography.Title>
          <div style={{ marginBottom: 16 }}>
            <Tag color="orange">评分 {scoreOf(shop.score)}</Tag>
            <Tag>月售 {shop.sold}</Tag>
            <Tag>评论 {shop.comments}</Tag>
            <Tag>¥{fenToYuan(shop.avgPrice)}/人</Tag>
          </div>
          <Descriptions column={1} size="small">
            <Descriptions.Item label="地址">{shop.area} {shop.address}</Descriptions.Item>
            <Descriptions.Item label="营业时间">{shop.openHours || '—'}</Descriptions.Item>
            <Descriptions.Item label="坐标">
              {shop.longitude ?? '—'}, {shop.latitude ?? '—'}
            </Descriptions.Item>
          </Descriptions>
        </Col>
      </Row>

      <Typography.Title level={4} style={{ marginTop: 32 }}>
        门店优惠券（{vouchers.length}）
      </Typography.Title>
      {vouchers.length === 0 ? (
        <Empty description="该商户暂无可领优惠券" />
      ) : (
        <List
          grid={{ gutter: 16, column: 2 }}
          dataSource={vouchers}
          renderItem={(v) => (
            <Card size="small" title={v.title} extra={<Tag color="gold">满 ¥{fenToYuan(v.payValue)} 可用</Tag>}>
              <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }} ellipsis={{ rows: 2 }}>
                {v.subTitle || v.rules}
              </Typography.Paragraph>
              <Row justify="space-between" align="middle">
                <Typography.Text strong style={{ color: '#fa541c', fontSize: 18 }}>
                  ¥{fenToYuan(v.actualValue)}
                </Typography.Text>
                <Button type="primary" loading={claimingId === v.id} onClick={() => handleClaim(v)}>
                  立即领取
                </Button>
              </Row>
            </Card>
          )}
        />
      )}
      <Button type="link" onClick={() => navigate('/')} style={{ paddingLeft: 0, marginTop: 16 }}>
        ← 返回商户列表
      </Button>
    </div>
  )
}
