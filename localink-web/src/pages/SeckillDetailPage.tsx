import { Button, Card, Col, Descriptions, Row, Skeleton, Tag, Typography, App } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import dayjs from 'dayjs'
import { useNavigate, useParams } from 'react-router-dom'
import { applySeckillToken, fetchSeckillVoucher, seckillOrder } from '../api/seckill'
import { useAuthStore } from '../stores/authStore'
import type { SeckillVoucherVO } from '../types/api'
import { fenToYuan } from '../utils/format'
import { phaseOf, PHASE_TAG } from '../utils/seckillPhase'

function countdownText(target: number, now: number): string {
  const diff = Math.max(0, target - now)
  const h = Math.floor(diff / 3_600_000)
  const m = Math.floor((diff % 3_600_000) / 60_000)
  const s = Math.floor((diff % 60_000) / 1000)
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

export default function SeckillDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { message: messageApi } = App.useApp()
  const [seckill, setSeckill] = useState<SeckillVoucherVO | null>(null)
  const [loading, setLoading] = useState(true)
  const [now, setNow] = useState(() => Date.now())
  const [token, setToken] = useState<string | null>(null)
  const [tokenExpireAt, setTokenExpireAt] = useState(0)
  const [applying, setApplying] = useState(false)
  const [ordering, setOrdering] = useState(false)
  const loggedIn = useAuthStore((s) => s.token != null)
  const userLevel = useAuthStore((s) => s.user?.level ?? 0)

  useEffect(() => {
    if (!id) return
    setLoading(true)
    fetchSeckillVoucher(id)
      .then(setSeckill)
      .finally(() => setLoading(false))
  }, [id])

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  const phase = useMemo(() => (seckill ? phaseOf(seckill, now) : 'before'), [seckill, now])
  const tokenLeftSec = tokenExpireAt > 0 ? Math.ceil((tokenExpireAt - now) / 1000) : 0

  const handleApplyToken = async () => {
    if (!seckill) return
    setApplying(true)
    try {
      const value = await applySeckillToken(seckill.voucherId)
      setToken(value)
      setTokenExpireAt(Date.now() + 30_000)
      messageApi.success(`令牌已发放，30 秒内有效`)
    } catch {
      // 限流 40008 / 未登录 40002 已由拦截器反馈或跳转
    } finally {
      setApplying(false)
    }
  }

  const handleOrder = async () => {
    if (!seckill || !token) {
      messageApi.warning('请先申请抢购令牌')
      return
    }
    setOrdering(true)
    try {
      const orderId = await seckillOrder(seckill.voucherId, token)
      messageApi.success(`抢购成功，订单号 ${orderId}，即将跳转我的订单`)
      setTimeout(() => navigate('/orders'), 1200)
    } catch {
      // 失败码（10002~10007/40008）已由拦截器 toast 后端 message
    } finally {
      setOrdering(false)
    }
  }

  if (loading) {
    return <Skeleton active paragraph={{ rows: 8 }} />
  }
  if (!seckill) {
    return <Typography.Title level={4}>秒杀活动不存在</Typography.Title>
  }

  const tag = PHASE_TAG[phase]
  const countdown =
    phase === 'before'
      ? countdownText(dayjs(seckill.beginTime, 'YYYY-MM-DD HH:mm:ss').valueOf(), now)
      : phase === 'running'
        ? countdownText(dayjs(seckill.endTime, 'YYYY-MM-DD HH:mm:ss').valueOf(), now)
        : null
  const countdownLabel = phase === 'before' ? '距开始' : '距结束'
  const levelInsufficient = seckill.minLevel > 0 && userLevel < seckill.minLevel

  return (
    <div>
      <Card>
        <Row justify="space-between" align="middle">
          <Col>
            <Typography.Title level={3} style={{ margin: 0 }}>
              {seckill.title}
            </Typography.Title>
            <div style={{ marginTop: 8 }}>
              <Tag color={tag.color}>{tag.text}</Tag>
              <Tag color="gold">库存 {seckill.stock}</Tag>
              {seckill.minLevel > 0 && (
                <Tag color={levelInsufficient ? 'volcano' : 'green'}>需 Lv.{seckill.minLevel}</Tag>
              )}
            </div>
          </Col>
          <Col style={{ textAlign: 'right' }}>
            <Typography.Text type="secondary">{countdownLabel}</Typography.Text>
            <Typography.Title level={2} style={{ margin: 0, fontFamily: 'monospace', color: phase === 'running' ? '#fa541c' : undefined }}>
              {countdown ?? '--:--:--'}
            </Typography.Title>
          </Col>
        </Row>
        <Descriptions column={2} style={{ marginTop: 24 }}>
          <Descriptions.Item label="秒杀价">
            <Typography.Text strong style={{ color: '#fa541c', fontSize: 18 }}>
              ¥{fenToYuan(seckill.payValue)}
            </Typography.Text>
            <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
              （券面值抵 ¥{fenToYuan(seckill.actualValue)}）
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="活动时间">
            {seckill.beginTime} ~ {seckill.endTime}
          </Descriptions.Item>
          {seckill.subTitle && <Descriptions.Item label="副标题">{seckill.subTitle}</Descriptions.Item>}
          {seckill.rules && <Descriptions.Item label="规则">{seckill.rules}</Descriptions.Item>}
        </Descriptions>
      </Card>

      <Card title="抢购（两步流：先申请令牌，再凭令牌下单）" style={{ marginTop: 16 }}>
        <Row gutter={16} align="middle">
          <Col>
            <Button
              size="large"
              disabled={phase !== 'running' || !loggedIn}
              loading={applying}
              onClick={handleApplyToken}
            >
              第一步：申请令牌
            </Button>
          </Col>
          <Col>
            <Button
              size="large"
              type="primary"
              danger
              disabled={phase !== 'running' || !loggedIn}
              loading={ordering}
              onClick={handleOrder}
            >
              第二步：立即抢购
            </Button>
          </Col>
          <Col flex="auto">
            {token ? (
              tokenLeftSec > 0 ? (
                <Typography.Text type="warning">令牌剩余 {tokenLeftSec}s（一次性，下单即消费）</Typography.Text>
              ) : (
                <Typography.Text type="danger">令牌已过期，请重新申请（过期令牌下单将被拒绝）</Typography.Text>
              )
            ) : (
              <Typography.Text type="secondary">尚未申请令牌；每人限领一次，重复抢购会被拒绝</Typography.Text>
            )}
          </Col>
        </Row>
        {!loggedIn && (
          <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
            未登录——
            <Button type="link" style={{ padding: 0 }} onClick={() => navigate('/login')}>
              去登录
            </Button>
            后才能参与抢购
          </Typography.Paragraph>
        )}
        {loggedIn && levelInsufficient && (
          <Typography.Paragraph type="warning" style={{ marginTop: 12, marginBottom: 0 }}>
            当前会员等级 Lv.{userLevel} 不满足 Lv.{seckill.minLevel}，抢购会被拒绝（以后端校验为准）
          </Typography.Paragraph>
        )}
      </Card>

      <Button type="link" onClick={() => navigate(-1)} style={{ paddingLeft: 0, marginTop: 16 }}>
        ← 返回
      </Button>
    </div>
  )
}
