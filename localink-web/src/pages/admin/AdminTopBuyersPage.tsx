import { Button, Card, Select, Space, Table, Typography } from 'antd'
import { useEffect, useState } from 'react'
import dayjs from 'dayjs'
import { pageShops } from '../../api/shop'
import { fetchTopBuyers } from '../../api/topBuyers'
import type { ShopVO, TopBuyerRow } from '../../types/api'

export default function AdminTopBuyersPage() {
  const [shops, setShops] = useState<ShopVO[]>([])
  const [shopId, setShopId] = useState<string | null>(null)
  const [date, setDate] = useState<string>(() => dayjs().format('YYYYMMDD'))
  const [rows, setRows] = useState<TopBuyerRow[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    pageShops(null, 1, 50).then((p) => {
      setShops(p.records)
      if (p.records.length > 0 && shopId == null) setShopId(p.records[0].id)
    })
  }, [shopId])

  const load = async (d: string) => {
    if (!shopId) return
    setLoading(true)
    try {
      setRows(await fetchTopBuyers(shopId, d))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (shopId) load(date)
  }, [shopId, date])

  const today = dayjs().format('YYYYMMDD')
  const yesterday = dayjs().subtract(1, 'day').format('YYYYMMDD')

  return (
    <Card title="店铺每日 Top 买家">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space>
          <Select
            style={{ width: 260 }}
            showSearch
            optionFilterProp="label"
            placeholder="选择商户"
            value={shopId}
            onChange={setShopId}
            options={shops.map((s) => ({ value: s.id, label: s.name }))}
          />
          <Select
            style={{ width: 120 }}
            value={date}
            onChange={setDate}
            options={[
              { value: today, label: '今天' },
              { value: yesterday, label: '昨天' },
            ]}
          />
          <Button loading={loading} onClick={() => load(date)}>刷新</Button>
        </Space>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          按日 ZSet 统计（下单 ZINCRBY，TTL 2 天自动清）——预通知圈名单的"店铺 Top10 买家"数据源。
        </Typography.Paragraph>
        <Table<TopBuyerRow>
          rowKey="userId"
          size="small"
          loading={loading}
          dataSource={rows}
          pagination={false}
          columns={[
            { title: '排名', width: 80, render: (_, __, i) => `#${i + 1}` },
            { title: '用户', dataIndex: 'nickName' },
            { title: '用户 ID', dataIndex: 'userId', width: 200, ellipsis: true },
            { title: '当日订单数', dataIndex: 'count', width: 120 },
          ]}
        />
      </Space>
    </Card>
  )
}
