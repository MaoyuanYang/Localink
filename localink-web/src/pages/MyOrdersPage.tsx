import { Button, Empty, List, Pagination, Row, Skeleton, Tag, Typography } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { pageMyOrders } from '../api/order'
import type { VoucherOrderVO } from '../types/api'

const ORDER_STATUS: Record<number, { color: string; text: string }> = {
  1: { color: 'blue', text: '已创建' },
  2: { color: 'default', text: '已取消' },
  3: { color: 'red', text: '超时关闭' },
}

export default function MyOrdersPage() {
  const [orders, setOrders] = useState<VoucherOrderVO[]>([])
  const [page, setPage] = useState(1)
  const [size] = useState(10)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)

  const load = useCallback(
    (target: number) => {
      setLoading(true)
      pageMyOrders(target, size)
        .then((p) => {
          setOrders(p.records)
          setTotal(p.total)
        })
        .catch(() => {
          // 未登录 40002 由拦截器跳登录；其余错误已 toast
        })
        .finally(() => setLoading(false))
    },
    [size],
  )

  useEffect(() => {
    load(page)
  }, [page, load])

  return (
    <div>
      <Row justify="space-between" align="middle" style={{ marginBottom: 16 }}>
        <Typography.Title level={3} style={{ margin: 0 }}>
          我的订单
        </Typography.Title>
        <Button onClick={() => load(page)} loading={loading}>
          刷新
        </Button>
      </Row>
      <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
        秒杀为异步建单（Kafka 消费端落库通常 1 秒内），刚下的订单若未出现请点刷新；"已创建"订单 15 分钟未支付将自动关闭。
      </Typography.Paragraph>
      <Skeleton loading={loading && orders.length === 0} active paragraph={{ rows: 6 }}>
        {orders.length === 0 && !loading ? (
          <Empty description="暂无订单（登录后领券/抢购的订单会显示在这里）" />
        ) : (
          <List
            dataSource={orders}
            renderItem={(order) => {
              const status = ORDER_STATUS[order.status] ?? { color: 'default', text: `状态 ${order.status}` }
              return (
                <List.Item actions={[<Tag key="s" color={status.color}>{status.text}</Tag>]}>
                  <List.Item.Meta
                    title={
                      <span>
                        {order.title ?? `券 ${order.voucherId}`}
                        {order.voucherType === 2 ? (
                          <Tag color="volcano" style={{ marginLeft: 8 }}>
                            秒杀
                          </Tag>
                        ) : (
                          <Tag style={{ marginLeft: 8 }}>普通</Tag>
                        )}
                      </span>
                    }
                    description={
                      <>
                        订单号 {order.id} · 下单 {order.createTime}
                        {order.closeTime ? ` · 关闭 ${order.closeTime}` : ''}
                      </>
                    }
                  />
                </List.Item>
              )
            }}
          />
        )}
      </Skeleton>
      <Pagination
        style={{ marginTop: 24, textAlign: 'right' }}
        current={page}
        pageSize={size}
        total={total}
        showTotal={(t) => `共 ${t} 单`}
        onChange={setPage}
      />
    </div>
  )
}
