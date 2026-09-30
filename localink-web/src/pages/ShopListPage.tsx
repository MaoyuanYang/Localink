import { Card, Col, Empty, List, Pagination, Row, Select, Skeleton, Tag, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchShopTypes, pageShops } from '../api/shop'
import type { ShopTypeVO, ShopVO } from '../types/api'
import { fenToYuan, firstImage, scoreOf } from '../utils/format'

export default function ShopListPage() {
  const navigate = useNavigate()
  const [types, setTypes] = useState<ShopTypeVO[]>([])
  const [typeId, setTypeId] = useState<string | null>(null)
  const [page, setPage] = useState(1)
  const [size] = useState(9)
  const [total, setTotal] = useState(0)
  const [shops, setShops] = useState<ShopVO[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetchShopTypes().then(setTypes).catch(() => setTypes([]))
  }, [])

  useEffect(() => {
    setLoading(true)
    pageShops(typeId, page, size)
      .then((p) => {
        setShops(p.records)
        setTotal(p.total)
      })
      .finally(() => setLoading(false))
  }, [typeId, page, size])

  return (
    <div>
      <Row justify="space-between" align="middle" style={{ marginBottom: 16 }}>
        <Typography.Title level={3} style={{ margin: 0 }}>
          商户
        </Typography.Title>
        <Select
          style={{ width: 180 }}
          placeholder="全部分类"
          allowClear
          value={typeId}
          onChange={(v) => {
            setTypeId(v ?? null)
            setPage(1)
          }}
          options={types.map((t) => ({ value: t.id, label: t.name }))}
        />
      </Row>
      <Skeleton loading={loading && shops.length === 0} active paragraph={{ rows: 6 }}>
        {shops.length === 0 && !loading ? (
          <Empty description="暂无商户" />
        ) : (
          <List
            grid={{ gutter: 16, column: 3 }}
            dataSource={shops}
            renderItem={(shop) => (
              <Col>
                <Card
                  hoverable
                  cover={
                    <img
                      alt={shop.name}
                      src={firstImage(shop.images) ?? '/placeholder-shop.svg'}
                      style={{ height: 180, objectFit: 'cover' }}
                      onError={(e) => {
                        e.currentTarget.src = '/placeholder-shop.svg'
                      }}
                      onClick={() => navigate(`/shop/${shop.id}`)}
                    />
                  }
                  onClick={() => navigate(`/shop/${shop.id}`)}
                >
                  <Card.Meta
                    title={shop.name}
                    description={
                      <>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          {shop.area} · {shop.address}
                        </Typography.Text>
                        <div style={{ marginTop: 8 }}>
                          <Tag color="orange">评分 {scoreOf(shop.score)}</Tag>
                          <Tag>月售 {shop.sold}</Tag>
                          <Tag>¥{fenToYuan(shop.avgPrice)}/人</Tag>
                        </div>
                      </>
                    }
                  />
                </Card>
              </Col>
            )}
          />
        )}
      </Skeleton>
      <Row justify="end" style={{ marginTop: 24 }}>
        <Pagination current={page} pageSize={size} total={total} showTotal={(t) => `共 ${t} 家`} onChange={setPage} />
      </Row>
    </div>
  )
}
