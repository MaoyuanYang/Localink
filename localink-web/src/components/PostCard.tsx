import { Card, Image, Space, Tag, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'
import type { PostVO } from '../types/api'

export default function PostCard({ post, rank }: { post: PostVO; rank?: number }) {
  const navigate = useNavigate()
  const preview = post.content.length > 120 ? post.content.slice(0, 120) + '…' : post.content
  return (
    <Card hoverable size="small" style={{ marginBottom: 12 }} onClick={() => navigate(`/post/${post.id}`)}>
      <Space size="middle">
        {rank !== undefined && (
          <Typography.Title level={3} style={{ margin: 0, width: 36, color: rank < 3 ? '#fa541c' : '#999', fontFamily: 'monospace' }}>
            #{rank + 1}
          </Typography.Title>
        )}
        <div style={{ flex: 1, minWidth: 0 }}>
          <Typography.Text strong style={{ fontSize: 15 }}>
            {post.title}
          </Typography.Text>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 6, marginTop: 4 }}>
            {preview}
          </Typography.Paragraph>
          {post.images.length > 0 && (
            <Space wrap size={6} style={{ marginBottom: 6 }}>
              {post.images.slice(0, 3).map((img) => (
                <Image
                  key={img}
                  src={img}
                  alt="帖子图片"
                  width={72}
                  height={72}
                  style={{ objectFit: 'cover', borderRadius: 6 }}
                  fallback="/placeholder-shop.svg"
                  preview={false}
                />
              ))}
              {post.images.length > 3 && <Typography.Text type="secondary">+{post.images.length - 3}</Typography.Text>}
            </Space>
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {post.nickName} · {post.createTime} · 赞 {post.liked} · 评论 {post.comments} · 浏览 {post.viewed}
          </Typography.Text>
        </div>
        {rank !== undefined && rank < 3 && <Tag color="volcano">热</Tag>}
      </Space>
    </Card>
  )
}
