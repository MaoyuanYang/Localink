import { Button, Card, Descriptions, Select, Space, Spin, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { pageShops } from '../../api/shop'
import { fetchSubscribeStats, listSeckillVouchers } from '../../api/seckill'
import type { SeckillVoucherVO, ShopVO, SubscribeStatsVO } from '../../types/api'

export default function AdminSubscribePage() {
  const [shops, setShops] = useState<ShopVO[]>([])
  const [shopId, setShopId] = useState<string | null>(null)
  const [seckills, setSeckills] = useState<SeckillVoucherVO[]>([])
  const [voucherId, setVoucherId] = useState<string | null>(null)
  const [stats, setStats] = useState<SubscribeStatsVO | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    pageShops(null, 1, 50).then((p) => {
      setShops(p.records)
      if (p.records.length > 0 && shopId == null) setShopId(p.records[0].id)
    })
  }, [shopId])

  useEffect(() => {
    if (!shopId) return
    setVoucherId(null)
    setStats(null)
    listSeckillVouchers(shopId).then(setSeckills)
  }, [shopId])

  const loadStats = async (id: string) => {
    setLoading(true)
    try {
      setStats(await fetchSubscribeStats(id))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (voucherId) loadStats(voucherId)
  }, [voucherId])

  return (
    <Card title="订阅提醒（预通知观测）">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space>
          <Select
            style={{ width: 240 }}
            placeholder="选择商户"
            showSearch
            optionFilterProp="label"
            value={shopId}
            onChange={setShopId}
            options={shops.map((s) => ({ value: s.id, label: s.name }))}
          />
          <Select
            style={{ width: 320 }}
            placeholder="选择秒杀活动"
            value={voucherId}
            onChange={setVoucherId}
            options={seckills.map((s) => ({ value: s.voucherId, label: `${s.title}（${s.beginTime} 起）` }))}
          />
          {voucherId && <Button onClick={() => loadStats(voucherId)} loading={loading}>刷新</Button>}
        </Space>

        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          预通知全自动：创建活动即投递延迟任务，开抢前 2 分钟到期圈名单（等级达标用户 + 店铺 Top 买家）写入各用户通知收件箱，SETNX 标记防重发。已知限制：编辑活动改开始时间不会重投预通知任务。
        </Typography.Paragraph>

        {loading && <Spin />}
        {stats && !loading && (
          <Descriptions bordered size="small" column={2}>
            <Descriptions.Item label="活动">{stats.title}</Descriptions.Item>
            <Descriptions.Item label="活动窗口">
              {stats.beginTime ?? '—'} ~ {stats.endTime ?? '—'}
            </Descriptions.Item>
            <Descriptions.Item label="订阅排队人数">{stats.queueSize}</Descriptions.Item>
            <Descriptions.Item label="已发券补位人数">{stats.grantedCount}</Descriptions.Item>
            <Descriptions.Item label="排队中（待补位）">{stats.subscribedCount}</Descriptions.Item>
            <Descriptions.Item label="预通知已发送">{stats.noticeSent ? '是' : '否'}</Descriptions.Item>
          </Descriptions>
        )}
      </Space>
    </Card>
  )
}
