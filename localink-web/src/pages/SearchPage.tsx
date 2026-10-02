import { Button, Card, Empty, Input, List, Radio, Skeleton, Space, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { searchPosts } from '../api/search'
import type { PostSearchVO, ShopFacetVO } from '../types/api'

export default function SearchPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [keyword, setKeyword] = useState(params.get('keyword') ?? '')
  const [sort, setSort] = useState<'relevance' | 'time'>('relevance')
  const [shopId, setShopId] = useState<string | undefined>(undefined)
  const [records, setRecords] = useState<PostSearchVO[]>([])
  const [nextSearchAfter, setNextSearchAfter] = useState<string | null>(null)
  const [facets, setFacets] = useState<ShopFacetVO[]>([])
  const [searched, setSearched] = useState(false)
  const [loading, setLoading] = useState(false)

  const run = async (opts?: { sort?: 'relevance' | 'time'; shopId?: string; searchAfter?: string }) => {
    if (!keyword.trim()) return
    setLoading(true)
    setRecords([])
    try {
      const vo = await searchPosts({
        keyword: keyword.trim(),
        sort: opts?.sort ?? sort,
        shopId: opts?.shopId ?? shopId,
        searchAfter: opts?.searchAfter,
        size: 10,
      })
      setFacets(vo.shopFacets)
      setNextSearchAfter(vo.nextSearchAfter)
      setRecords(vo.records)
      setSearched(true)
    } finally {
      setLoading(false)
    }
  }

  const loadMore = async () => {
    if (!keyword.trim() || nextSearchAfter == null) return
    setLoading(true)
    try {
      const vo = await searchPosts({
        keyword: keyword.trim(),
        sort,
        shopId,
        searchAfter: nextSearchAfter,
        size: 10,
      })
      setRecords((prev) => [...prev, ...vo.records])
      setNextSearchAfter(vo.nextSearchAfter)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ display: 'flex', gap: 16 }}>
      <div style={{ flex: 1 }}>
        <Space.Compact style={{ width: '100%', marginBottom: 12 }}>
          <Input
            size="large"
            placeholder="搜索帖子（标题/正文，IK 分词，匿名可搜）"
            value={keyword}
            maxLength={64}
            onChange={(e) => setKeyword(e.target.value)}
            onPressEnter={() => run()}
          />
          <Button size="large" type="primary" loading={loading && !searched} onClick={() => run()}>
            搜索
          </Button>
        </Space.Compact>
        <Radio.Group
          value={sort}
          onChange={(e) => {
            const next = e.target.value as 'relevance' | 'time'
            setSort(next)
            if (searched) run({ sort: next })
          }}
          style={{ marginBottom: 12 }}
          disabled={!searched}
        >
          <Radio.Button value="relevance">按相关度</Radio.Button>
          <Radio.Button value="time">按时间</Radio.Button>
        </Radio.Group>
        {shopId && (
          <Tag
            closable
            onClose={() => {
              setShopId(undefined)
              if (searched) run({ shopId: undefined })
            }}
            style={{ marginBottom: 12 }}
          >
            仅看商户 {facets.find((f) => f.shopId === shopId)?.shopName ?? shopId}
          </Tag>
        )}
        {loading && <Skeleton active paragraph={{ rows: 6 }} style={{ marginBottom: 12 }} />}
        {!loading && !searched && (
          <Empty description="输入关键词开始搜索（标题/正文全文检索，结果高亮）" style={{ marginTop: 48 }} />
        )}
        {!loading && searched && records.length === 0 && <Empty description="没有匹配的帖子" />}
        {!loading && (
          <List
            dataSource={records}
            renderItem={(r) => (
              <Card size="small" hoverable style={{ marginBottom: 10 }} onClick={() => navigate(`/post/${r.id}`)}>
                <div
                  style={{ fontSize: 15, fontWeight: 600 }}
                  dangerouslySetInnerHTML={{ __html: r.titleHighlight ?? r.title }}
                />
                {r.contentHighlight && (
                  <div
                    style={{ marginTop: 4, marginBottom: 4, color: 'rgba(0,0,0,0.45)' }}
                    dangerouslySetInnerHTML={{ __html: r.contentHighlight }}
                  />
                )}
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {r.nickName} · {r.createTime} · 赞 {r.liked}
                </Typography.Text>
              </Card>
            )}
          />
        )}
        {nextSearchAfter != null && (
          <Button block loading={loading} onClick={loadMore}>
            加载更多
          </Button>
        )}
      </div>
      <Card size="small" title="按商户过滤（聚合全集）" style={{ width: 260 }}>
        {facets.length === 0 ? (
          <Typography.Text type="secondary">搜索后显示</Typography.Text>
        ) : (
          facets.map((f) => (
            <Typography.Paragraph key={f.shopId} style={{ marginBottom: 6 }}>
              <a
                onClick={() => {
                  setShopId(f.shopId)
                  if (searched) run({ shopId: f.shopId })
                }}
              >
                {f.shopName}
              </a>
              <Typography.Text type="secondary">（{f.count}）</Typography.Text>
            </Typography.Paragraph>
          ))
        )}
      </Card>
    </div>
  )
}
