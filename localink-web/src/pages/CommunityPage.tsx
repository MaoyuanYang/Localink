import { Button, Card, Empty, Form, Input, Modal, Select, Skeleton, Space, Tabs, Typography, Upload, App } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { UploadFile } from 'antd'
import { fetchFeed } from '../api/feed'
import { createPost, fetchHotPosts, uploadImage } from '../api/post'
import { pageShops } from '../api/shop'
import { signToday, fetchSignStatus } from '../api/user'
import PostCard from '../components/PostCard'
import { useAuthStore } from '../stores/authStore'
import type { PostVO, ShopVO, SignVO } from '../types/api'

interface PostFormValues {
  title: string
  content: string
  shopId?: string
}

export default function CommunityPage() {
  const navigate = useNavigate()
  const { message: messageApi } = App.useApp()
  const loggedIn = useAuthStore((s) => s.token != null)
  const [sign, setSign] = useState<SignVO | null>(null)
  const [signing, setSigning] = useState(false)
  const [feed, setFeed] = useState<PostVO[]>([])
  const [nextCursor, setNextCursor] = useState<number | null>(null)
  const [feedLoading, setFeedLoading] = useState(false)
  const [hot, setHot] = useState<PostVO[]>([])
  const [hotLoading, setHotLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [shops, setShops] = useState<ShopVO[]>([])
  const [fileList, setFileList] = useState<UploadFile[]>([])
  const [uploading, setUploading] = useState(false)
  const [form] = Form.useForm<PostFormValues>()

  useEffect(() => {
    if (loggedIn) {
      fetchSignStatus().then(setSign).catch(() => setSign(null))
      loadFeed(null)
    }
  }, [loggedIn])

  useEffect(() => {
    setHotLoading(true)
    fetchHotPosts(10)
      .then(setHot)
      .finally(() => setHotLoading(false))
  }, [])

  const loadFeed = useCallback((cursor: number | null) => {
    setFeedLoading(true)
    fetchFeed(cursor, 5)
      .then((r) => {
        setFeed((prev) => (cursor == null ? r.records : [...prev, ...r.records]))
        setNextCursor(r.nextCursor)
      })
      .catch(() => {
        // 40002 已跳登录
      })
      .finally(() => setFeedLoading(false))
  }, [])

  const handleSign = async () => {
    setSigning(true)
    try {
      const vo = await signToday()
      setSign(vo)
      messageApi.success(vo.signedToday ? `签到成功，已连续 ${vo.continuousDays} 天` : '今日已签到（幂等）')
    } catch {
      // 已 toast
    } finally {
      setSigning(false)
    }
  }

  const openCreate = () => {
    if (shops.length === 0) {
      pageShops(null, 1, 50).then((p) => setShops(p.records)).catch(() => setShops([]))
    }
    form.resetFields()
    setFileList([])
    setModalOpen(true)
  }

  const handleSubmit = async (values: PostFormValues) => {
    const images = fileList.map((f) => f.response as string).filter(Boolean)
    if (images.length > 9) {
      messageApi.warning('图片最多 9 张')
      return
    }
    setUploading(true)
    try {
      await createPost({
        title: values.title,
        content: values.content,
        images: images.length > 0 ? images.join(',') : undefined,
        shopId: values.shopId,
      })
      messageApi.success('发布成功（先发后审：隐性风险词将被自动驳回）')
      setModalOpen(false)
      loadFeed(null)
    } catch {
      // 30001 敏感词等已 toast
    } finally {
      setUploading(false)
    }
  }

  return (
    <div>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {loggedIn && sign && (
          <Card size="small">
            <Space size="large">
              <Typography.Text>
                今日{sign.signedToday ? '已' : '未'}签到 · 连续 <Typography.Text strong>{sign.continuousDays}</Typography.Text> 天 · 本月累计 {sign.monthDays} 天
              </Typography.Text>
              <Button type="primary" size="small" disabled={sign.signedToday} loading={signing} onClick={handleSign}>
                {sign.signedToday ? '已签到' : '签到'}
              </Button>
            </Space>
          </Card>
        )}

        <Tabs
          tabBarExtraContent={
            loggedIn && (
              <Button type="primary" onClick={openCreate}>
                发布帖子
              </Button>
            )
          }
          items={[
            {
              key: 'feed',
              label: '关注流 Feed',
              children: (
                <>
                  {!loggedIn ? (
                    <Empty description={<span>登录后查看关注流 <Button type="link" style={{ padding: 0 }} onClick={() => navigate('/login')}>去登录</Button></span>} />
                  ) : (
                    <>
                      {feedLoading && feed.length === 0 && <Skeleton active paragraph={{ rows: 6 }} style={{ marginBottom: 12 }} />}
                      {feed.length === 0 && !feedLoading && nextCursor === null && (
                        <Empty description={<span>关注的人还没有动态——去逛逛商户或发布第一篇帖子</span>} style={{ marginTop: 32 }} />
                      )}
                      {feed.map((p) => (
                        <PostCard key={p.id} post={p} />
                      ))}
                      {nextCursor != null && (
                        <Button block loading={feedLoading} onClick={() => loadFeed(nextCursor)}>
                          加载更多
                        </Button>
                      )}
                      {feed.length > 0 && nextCursor == null && (
                        <Typography.Text type="secondary" style={{ display: 'block', textAlign: 'center' }}>
                          没有更多了
                        </Typography.Text>
                      )}
                    </>
                  )}
                </>
              ),
            },
            {
              key: 'hot',
              label: '热榜',
              children: (
                <>
                  <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
                    热度 =（赞×5 + 评论×3 + 浏览×1）× e^(−ln2/72h·Δt)，半衰期 72 小时，每 5 分钟全量重算。
                  </Typography.Paragraph>
                  {hotLoading ? <Skeleton active paragraph={{ rows: 5 }} /> : hot.length === 0 ? <Empty description="暂无上榜帖子" /> : hot.map((p, i) => <PostCard key={p.id} post={p} rank={i} />)}
                </>
              ),
            },
          ]}
        />
      </Space>

      <Modal
        title="发布帖子"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={uploading}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleSubmit}>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '必填' }, { max: 255, message: '最长 255 字' }]}>
            <Input placeholder="一句话标题" />
          </Form.Item>
          <Form.Item name="content" label="内容" rules={[{ required: true, message: '必填' }, { max: 2048, message: '最长 2048 字' }]}>
            <Input.TextArea rows={4} placeholder="分享你的探店体验…" />
          </Form.Item>
          <Form.Item name="shopId" label="关联商户（可选）">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="选择商户"
              options={shops.map((s) => ({ value: s.id, label: s.name }))}
            />
          </Form.Item>
          <Form.Item label="图片（最多 9 张）">
            <Upload
              listType="picture-card"
              fileList={fileList}
              maxCount={9}
              customRequest={async ({ file, onSuccess, onError }) => {
                try {
                  const url = await uploadImage(file as File)
                  onSuccess?.(url)
                } catch (e) {
                  onError?.(e as Error)
                }
              }}
              onChange={({ fileList: fl }) => setFileList(fl)}
            >
              {fileList.length < 9 && '+ 上传'}
            </Upload>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
