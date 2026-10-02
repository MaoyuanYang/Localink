import { Card, Select, Space, Table, Typography } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { adminPagePosts } from '../../api/post'
import type { PostVO } from '../../types/api'

export default function AdminAuditPage() {
  const [auditStatus, setAuditStatus] = useState<number | null>(null)
  const [posts, setPosts] = useState<PostVO[]>([])
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    adminPagePosts(auditStatus, page, 10)
      .then((p) => {
        setPosts(p.records)
        setTotal(p.total)
      })
      .finally(() => setLoading(false))
  }, [auditStatus, page])

  useEffect(() => {
    load()
  }, [load])

  return (
    <Card title="内容审核队列（观测）">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          两级审核全自动：显性词库发帖同步拦截（30001 拒发）；其余先发后审（audit=1 即刻可见），隐性风险词由 MQ 异步复审秒级驳回（audit=2，帖子从详情/Feed/热榜/搜索全端消失）。
          <Typography.Text strong>不存在"待审 0"队列</Typography.Text>——中间态在 MQ 内秒级流转不落库。
        </Typography.Paragraph>
        <Select
          style={{ width: 200 }}
          value={auditStatus}
          onChange={(v) => {
            setAuditStatus(v ?? null)
            setPage(1)
          }}
          allowClear
          placeholder="全部状态"
          options={[
            { value: 1, label: '在架（audit=1）' },
            { value: 2, label: '已驳回（audit=2）' },
          ]}
        />
        <Table<PostVO>
          rowKey="id"
          size="small"
          loading={loading}
          dataSource={posts}
          pagination={{ current: page, pageSize: 10, total, onChange: setPage, showTotal: (t) => `共 ${t} 帖` }}
          columns={[
            { title: 'ID', dataIndex: 'id', width: 180, ellipsis: true },
            { title: '标题', dataIndex: 'title', ellipsis: true },
            { title: '作者', dataIndex: 'nickName', width: 120 },
            { title: '审核状态', dataIndex: 'auditStatus', width: 100, render: (v: number) => (v === 1 ? '在架' : v === 2 ? '已驳回' : v) },
            { title: '赞/评/浏', width: 110, render: (_, p) => `${p.liked}/${p.comments}/${p.viewed}` },
            { title: '发布时间', dataIndex: 'createTime', width: 160 },
          ]}
        />
      </Space>
    </Card>
  )
}
