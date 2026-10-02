import { Avatar, Button, Card, Empty, Image, Input, List, Modal, Pagination, Popconfirm, Space, Typography, App } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { deleteComment, deletePost, fetchPost, likePost, pageComments, unlikePost, createComment } from '../api/post'
import { fetchCommonFollows, followUser, unfollowUser } from '../api/follow'
import { useAuthStore } from '../stores/authStore'
import type { CommentVO, PostVO, UserBriefVO } from '../types/api'

export default function PostDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { message: messageApi } = App.useApp()
  const me = useAuthStore((s) => s.user)
  const loggedIn = useAuthStore((s) => s.token != null)
  const [post, setPost] = useState<PostVO | null>(null)
  const [loading, setLoading] = useState(true)
  const [comments, setComments] = useState<CommentVO[]>([])
  const [commentTotal, setCommentTotal] = useState(0)
  const [commentPage, setCommentPage] = useState(1)
  const [liked, setLiked] = useState(false)
  const [commentInput, setCommentInput] = useState('')
  const [replyTo, setReplyTo] = useState<CommentVO | null>(null)
  const [following, setFollowing] = useState(false)
  const [commonOpen, setCommonOpen] = useState(false)
  const [commonFollows, setCommonFollows] = useState<UserBriefVO[] | null>(null)

  const loadPost = useCallback(() => {
    if (!id) return
    setLoading(true)
    fetchPost(id)
      .then((p) => {
        setPost(p)
        setLiked(p.meLiked === true)
      })
      .finally(() => setLoading(false))
  }, [id])

  const loadComments = useCallback(() => {
    if (!id) return
    pageComments(id, commentPage, 10)
      .then((p) => {
        setComments(p.records)
        setCommentTotal(p.total)
      })
      .catch(() => setComments([]))
  }, [id, commentPage])

  useEffect(() => {
    loadPost()
  }, [loadPost])

  useEffect(() => {
    loadComments()
  }, [loadComments])

  const handleLike = async () => {
    if (!post) return
    if (liked) {
      const n = await unlikePost(post.id)
      setLiked(false)
      setPost({ ...post, liked: n })
    } else {
      const n = await likePost(post.id)
      setLiked(true)
      setPost({ ...post, liked: n })
    }
  }

  const handleDeletePost = async () => {
    if (!post) return
    await deletePost(post.id)
    messageApi.success('帖子已删除')
    navigate('/community')
  }

  const handleFollow = async () => {
    if (!post) return
    if (following) {
      await unfollowUser(post.userId)
      setFollowing(false)
      messageApi.success('已取消关注')
    } else {
      await followUser(post.userId)
      setFollowing(true)
      messageApi.success('已关注')
    }
  }

  const openCommon = async () => {
    if (!post) return
    setCommonOpen(true)
    setCommonFollows(null)
    try {
      setCommonFollows(await fetchCommonFollows(post.userId))
    } catch {
      setCommonOpen(false)
    }
  }

  const submitComment = async () => {
    if (!post || !commentInput.trim()) return
    await createComment({
      postId: post.id,
      content: commentInput.trim(),
      parentId: replyTo ? (replyTo.parentId !== '0' ? replyTo.parentId : replyTo.id) : undefined,
      replyId: replyTo?.id,
    })
    messageApi.success(replyTo ? `已回复 @${replyTo.nickName}` : '评论成功')
    setCommentInput('')
    setReplyTo(null)
    loadComments()
    loadPost()
  }

  const handleDeleteComment = async (c: CommentVO) => {
    await deleteComment(c.id)
    messageApi.success('评论已删除')
    loadComments()
    loadPost()
  }

  if (loading) {
    return <Typography.Paragraph>加载中…</Typography.Paragraph>
  }
  if (!post) {
    return <Empty description="帖子不存在或未过审" />
  }

  const isMine = me?.id === post.userId

  return (
    <div>
      <Card
        title={post.title}
        extra={
          <Space>
            {isMine && (
              <Popconfirm title="删除这篇帖子？" onConfirm={handleDeletePost}>
                <Button size="small" danger>删除</Button>
              </Popconfirm>
            )}
            <Button size="small" onClick={() => navigate('/community')}>返回</Button>
          </Space>
        }
      >
        <Space style={{ marginBottom: 12 }}>
          <Avatar style={{ backgroundColor: '#1677ff' }}>
            {post.nickName.slice(0, 1)}
          </Avatar>
          <div>
            <Typography.Text strong>{post.nickName}</Typography.Text>
            <Typography.Paragraph type="secondary" style={{ margin: 0, fontSize: 12 }}>
              {post.createTime} · 浏览 {post.viewed}
            </Typography.Paragraph>
          </div>
          {!isMine && loggedIn && (
            <Space>
              <Button size="small" type={following ? 'default' : 'primary'} onClick={handleFollow}>
                {following ? '已关注（点击取关）' : '+ 关注'}
              </Button>
              <Button size="small" onClick={openCommon}>共同关注</Button>
            </Space>
          )}
        </Space>
        <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>{post.content}</Typography.Paragraph>
        {post.images.length > 0 && (
          <Image.PreviewGroup>
            <Space wrap>
              {post.images.map((img) => (
                <Image key={img} src={img} alt="帖子图片" width={200} style={{ objectFit: 'cover', borderRadius: 8 }} fallback="/placeholder-shop.svg" />
              ))}
            </Space>
          </Image.PreviewGroup>
        )}
        <Space style={{ marginTop: 16 }}>
          <Button type={liked ? 'primary' : 'default'} danger={liked} onClick={handleLike}>
            {liked ? '❤ 已赞' : '♡ 点赞'} {post.liked}
          </Button>
          <Typography.Text type="secondary">评论 {post.comments}</Typography.Text>
        </Space>
      </Card>

      <Card title={`评论（${commentTotal}）`} style={{ marginTop: 16 }}>
        {loggedIn ? (
          <Space.Compact style={{ width: '100%', marginBottom: 16 }}>
            <Input
              placeholder={replyTo ? `回复 @${replyTo.nickName}…` : '写下你的评论…'}
              value={commentInput}
              maxLength={512}
              onChange={(e) => setCommentInput(e.target.value)}
              onPressEnter={submitComment}
            />
            <Button type="primary" onClick={submitComment}>{replyTo ? '回复' : '评论'}</Button>
          </Space.Compact>
        ) : (
          <Typography.Paragraph type="secondary">
            登录后参与评论 <Button type="link" style={{ padding: 0 }} onClick={() => navigate('/login')}>去登录</Button>
          </Typography.Paragraph>
        )}
        {replyTo && (
          <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
            正在回复 @{replyTo.nickName}：
            <Button type="link" size="small" style={{ padding: 0 }} onClick={() => setReplyTo(null)}>取消</Button>
          </Typography.Paragraph>
        )}
        <List
          dataSource={comments}
          locale={{ emptyText: '暂无评论' }}
          renderItem={(c) => (
            <CommentItem comment={c} meId={me?.id} onReply={setReplyTo} onDelete={handleDeleteComment} />
          )}
        />
        <Pagination
          style={{ marginTop: 16, textAlign: 'right' }}
          current={commentPage}
          pageSize={10}
          total={commentTotal}
          size="small"
          onChange={setCommentPage}
        />
      </Card>

      <Modal title="共同关注" open={commonOpen} onCancel={() => setCommonOpen(false)} footer={null}>
        {commonFollows === null ? (
          <Typography.Text>加载中…</Typography.Text>
        ) : commonFollows.length === 0 ? (
          <Typography.Text type="secondary">暂无共同关注</Typography.Text>
        ) : (
          <List
            dataSource={commonFollows}
            renderItem={(u) => (
              <List.Item>
                <List.Item.Meta avatar={<Avatar>{u.nickName.slice(0, 1)}</Avatar>} title={u.nickName} />
              </List.Item>
            )}
          />
        )}
      </Modal>
    </div>
  )
}

function CommentItem({
  comment,
  meId,
  onReply,
  onDelete,
}: {
  comment: CommentVO
  meId?: string
  onReply: (c: CommentVO) => void
  onDelete: (c: CommentVO) => void
}) {
  return (
    <List.Item
      actions={[
        <Button key="reply" type="link" size="small" style={{ padding: 0 }} onClick={() => onReply(comment)}>
          回复
        </Button>,
        ...(meId === comment.userId
          ? [
              <Popconfirm key="del" title="删除这条评论？" onConfirm={() => onDelete(comment)}>
                <Button type="link" size="small" danger style={{ padding: 0 }}>删除</Button>
              </Popconfirm>,
            ]
          : []),
      ]}
    >
      <List.Item.Meta
        avatar={<Avatar size="small">{comment.nickName.slice(0, 1)}</Avatar>}
        title={
          <span style={{ fontSize: 13 }}>
            {comment.nickName}
            <Typography.Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>{comment.createTime}</Typography.Text>
          </span>
        }
        description={<span style={{ color: 'rgba(0,0,0,0.88)' }}>{comment.content}</span>}
      />
      {comment.children && comment.children.length > 0 && (
        <List
          size="small"
          dataSource={comment.children}
          renderItem={(child) => (
            <List.Item
              actions={[
                <Button key="r" type="link" size="small" style={{ padding: 0 }} onClick={() => onReply(child)}>
                  回复
                </Button>,
                ...(meId === child.userId
                  ? [
                      <Popconfirm key="d" title="删除这条回复？" onConfirm={() => onDelete(child)}>
                        <Button type="link" size="small" danger style={{ padding: 0 }}>删除</Button>
                      </Popconfirm>,
                    ]
                  : []),
              ]}
            >
              <Typography.Text style={{ fontSize: 13 }}>
                <Typography.Text strong style={{ fontSize: 13 }}>{child.nickName}</Typography.Text>
                {child.replyNickName ? <Typography.Text type="secondary"> 回复 @{child.replyNickName}：</Typography.Text> : '：'}
                {child.content}
              </Typography.Text>
            </List.Item>
          )}
        />
      )}
    </List.Item>
  )
}
