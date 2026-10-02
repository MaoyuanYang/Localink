import { Button, Card, Select, Space, Table, Tabs, Typography } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { adminPageReconcileLogs, adminPageRollbackFailures } from '../../api/reconcile'
import type { ReconcileLogVO, RollbackFailureVO } from '../../types/api'

const STATUS_TEXT: Record<number, { text: string; color?: string }> = {
  1: { text: '待处理' },
  2: { text: '异常' },
  3: { text: '不一致' },
  4: { text: '一致', color: '#52c41a' },
}

const LOG_TYPE_TEXT: Record<number, string> = { 1: '扣减', 2: '恢复' }

export default function AdminReconcilePage() {
  return (
    <Card title="对账看板（Redis 流水 ↔ DB 订单）">
      <Typography.Paragraph type="secondary">
        每 5 分钟单向比对：Redis 扣减流水 → DB 订单（宽限期 2 分钟只护差异判定）；"Redis 扣了但 DB 无单"触发补偿回滚（复用统一回滚链路）；
        终态订单流水未恢复补回滚；一致行翻牌 status=4。回滚执行失败落失败表，对账周期重试（成功删行、失败累加 retry_attempts，行龄超 24h 告警）。
      </Typography.Paragraph>
      <Tabs
        items={[
          { key: 'logs', label: '对账流水', children: <LogsTab /> },
          { key: 'failures', label: '回滚失败表', children: <FailuresTab /> },
        ]}
      />
    </Card>
  )
}

function LogsTab() {
  const [status, setStatus] = useState<number | null>(null)
  const [rows, setRows] = useState<ReconcileLogVO[]>([])
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    adminPageReconcileLogs(status, page, 10)
      .then((p) => {
        setRows(p.records)
        setTotal(p.total)
      })
      .finally(() => setLoading(false))
  }, [status, page])

  useEffect(() => {
    load()
  }, [load])

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space>
        <Select
          style={{ width: 180 }}
          value={status}
          allowClear
          placeholder="全部状态"
          onChange={(v) => {
            setStatus(v ?? null)
            setPage(1)
          }}
          options={[
            { value: 1, label: '待处理' },
            { value: 4, label: '一致' },
          ]}
        />
        <Button onClick={load} loading={loading}>刷新</Button>
      </Space>
      <Table<ReconcileLogVO>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={rows}
        pagination={{ current: page, pageSize: 10, total, onChange: setPage, showTotal: (t) => `共 ${t} 行` }}
        columns={[
          { title: '订单', dataIndex: 'orderId', width: 170, ellipsis: true },
          { title: '用户', dataIndex: 'userId', width: 170, ellipsis: true },
          { title: '类型', dataIndex: 'logType', width: 70, render: (v: number) => LOG_TYPE_TEXT[v] ?? v },
          { title: '库存变化', width: 110, render: (_, r) => `${r.beforeQty} → ${r.afterQty}（${r.changeQty > 0 ? '+' : ''}${r.changeQty}）` },
          {
            title: '对账状态',
            dataIndex: 'reconciliationStatus',
            width: 90,
            render: (v: number) => {
              const s = STATUS_TEXT[v] ?? { text: String(v) }
              return <span style={{ color: s.color }}>{s.text}</span>
            },
          },
          { title: '明细', dataIndex: 'detail', ellipsis: true },
          { title: '时间', dataIndex: 'createTime', width: 160 },
        ]}
      />
    </Space>
  )
}

function FailuresTab() {
  const [rows, setRows] = useState<RollbackFailureVO[]>([])
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    adminPageRollbackFailures(page, 10)
      .then((p) => {
        setRows(p.records)
        setTotal(p.total)
      })
      .finally(() => setLoading(false))
  }, [page])

  useEffect(() => {
    load()
  }, [load])

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Text type="secondary">
        现存失败行（成功重试的行已被删除）；行龄超 24 小时将触发 error 告警。
      </Typography.Text>
      <Table<RollbackFailureVO>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={rows}
        pagination={{ current: page, pageSize: 10, total, onChange: setPage, showTotal: (t) => `共 ${t} 行` }}
        columns={[
          { title: '券', dataIndex: 'voucherId', width: 170, ellipsis: true },
          { title: '用户', dataIndex: 'userId', width: 170, ellipsis: true },
          { title: '来源', dataIndex: 'source', width: 130 },
          { title: '重试次数', dataIndex: 'retryAttempts', width: 90 },
          { title: '结果码', dataIndex: 'resultCode', width: 80 },
          { title: '明细', dataIndex: 'detail', ellipsis: true },
          { title: '落表时间', dataIndex: 'createTime', width: 160 },
        ]}
      />
    </Space>
  )
}
