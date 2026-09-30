import { Button, Card, Select, Space, Table, Typography, App } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { pageShops } from '../../api/shop'
import { listSeckillVouchers } from '../../api/seckill'
import { adminCloseOrder, adminPageOrdersByVoucher } from '../../api/order'
import type { Page, SeckillVoucherVO, ShopVO, VoucherOrderVO } from '../../types/api'

const ORDER_STATUS: Record<number, { color: string; text: string }> = {
  1: { color: 'blue', text: '已创建' },
  2: { color: 'default', text: '已取消' },
  3: { color: 'red', text: '超时关闭' },
}

export default function AdminOrdersPage() {
  const { message: messageApi } = App.useApp()
  const [shops, setShops] = useState<ShopVO[]>([])
  const [shopId, setShopId] = useState<string | null>(null)
  const [seckills, setSeckills] = useState<SeckillVoucherVO[]>([])
  const [voucherId, setVoucherId] = useState<string | null>(null)
  const [pageData, setPageData] = useState<Page<VoucherOrderVO> | null>(null)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [closingId, setClosingId] = useState<string | null>(null)

  useEffect(() => {
    pageShops(null, 1, 50).then((p) => {
      setShops(p.records)
      if (p.records.length > 0 && shopId == null) setShopId(p.records[0].id)
    })
  }, [shopId])

  useEffect(() => {
    if (!shopId) return
    setVoucherId(null)
    listSeckillVouchers(shopId).then(setSeckills)
  }, [shopId])

  const load = useCallback(() => {
    if (!voucherId) return
    setLoading(true)
    adminPageOrdersByVoucher(voucherId, page, 20)
      .then(setPageData)
      .finally(() => setLoading(false))
  }, [voucherId, page])

  useEffect(() => {
    load()
  }, [load])

  const handleClose = async (order: VoucherOrderVO) => {
    setClosingId(order.id)
    try {
      const closed = await adminCloseOrder(order.id)
      if (closed) {
        messageApi.success('订单已关闭：库存回补、资格回滚、订阅者自动回流发券')
      } else {
        messageApi.info('订单已非创建态，跳过（幂等）')
      }
      load()
    } catch {
      // 已 toast
    } finally {
      setClosingId(null)
    }
  }

  return (
    <Card title="订单监控（按秒杀活动）">
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
            onChange={(v) => { setVoucherId(v); setPage(1) }}
            options={seckills.map((s) => ({ value: s.voucherId, label: `${s.title}（${s.beginTime} 起）` }))}
          />
          <Button onClick={load} loading={loading}>刷新</Button>
        </Space>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          正路：下单后 15 分钟未支付由延迟队列自动关单（状态→超时关闭，库存回流）；"模拟超时关单"是运营补偿/演示入口，立即执行同一套关单动作。
        </Typography.Paragraph>
        {voucherId && (
          <Table<VoucherOrderVO>
            rowKey="id"
            size="small"
            loading={loading}
            dataSource={pageData?.records ?? []}
            pagination={{
              current: page,
              pageSize: 20,
              total: pageData?.total ?? 0,
              onChange: setPage,
              showTotal: (t) => `共 ${t} 单`,
            }}
            columns={[
              { title: '订单号', dataIndex: 'id', width: 200, ellipsis: true },
              { title: '用户', dataIndex: 'userId', width: 190, ellipsis: true },
              { title: '券名', dataIndex: 'title', ellipsis: true },
              { title: '下单时间', dataIndex: 'createTime', width: 160 },
              { title: '关闭时间', dataIndex: 'closeTime', width: 160, render: (v: string | null) => v ?? '—' },
              {
                title: '状态',
                dataIndex: 'status',
                width: 100,
                render: (v: number) => {
                  const s = ORDER_STATUS[v] ?? { color: 'default', text: `状态 ${v}` }
                  return <span style={{ color: s.color === 'default' ? undefined : s.color }}>{s.text}</span>
                },
              },
              {
                title: '操作',
                width: 140,
                render: (_, order) =>
                  order.status === 1 ? (
                    <Button size="small" danger loading={closingId === order.id} onClick={() => handleClose(order)}>
                      模拟超时关单
                    </Button>
                  ) : null,
              },
            ]}
          />
        )}
      </Space>
    </Card>
  )
}
