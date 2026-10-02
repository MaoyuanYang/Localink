import { Button, Card, DatePicker, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tabs, App } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import dayjs, { type Dayjs } from 'dayjs'
import { pageShops } from '../../api/shop'
import { createVoucher, deleteVoucher, listVouchers, updateVoucher } from '../../api/voucher'
import { createSeckillVoucher, deleteSeckillVoucher, listSeckillVouchers, updateSeckillVoucher } from '../../api/seckill'
import type { SeckillVoucherVO, ShopVO, VoucherVO } from '../../types/api'
import { fenToYuan } from '../../utils/format'
import { PHASE_TAG, phaseOf } from '../../utils/seckillPhase'

interface AmountFormValues {
  id?: string
  title: string
  subTitle?: string
  rules?: string
  payYuan: number
  actualYuan: number
  status?: number
}

interface SeckillFormValues extends AmountFormValues {
  stock: number
  minLevel: number
  range?: [Dayjs | null, Dayjs | null]
}

function toCents(yuan: number): number {
  return Math.round(yuan * 100)
}

function centsToYuan(cents: number): number {
  return cents / 100
}

export default function AdminVouchersPage() {
  const [shops, setShops] = useState<ShopVO[]>([])
  const [shopId, setShopId] = useState<string | null>(null)

  useEffect(() => {
    pageShops(null, 1, 50).then((p) => {
      setShops(p.records)
      if (p.records.length > 0 && shopId == null) {
        setShopId(p.records[0].id)
      }
    })
  }, [shopId])

  return (
    <Card title="券与活动管理（在架口径：已下架券不出现在列表）">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Select
          style={{ width: 320 }}
          placeholder="选择商户"
          showSearch
          optionFilterProp="label"
          value={shopId}
          onChange={setShopId}
          options={shops.map((s) => ({ value: s.id, label: s.name }))}
        />
        {shopId && (
          <Tabs
            items={[
              { key: 'normal', label: '普通券', children: <NormalVoucherTab shopId={shopId} /> },
              { key: 'seckill', label: '秒杀活动', children: <SeckillVoucherTab shopId={shopId} /> },
            ]}
          />
        )}
      </Space>
    </Card>
  )
}

function NormalVoucherTab({ shopId }: { shopId: string }) {
  const { message: messageApi } = App.useApp()
  const [vouchers, setVouchers] = useState<VoucherVO[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [editing, setEditing] = useState<VoucherVO | null>(null)
  const [form] = Form.useForm<AmountFormValues>()

  const load = useCallback(() => {
    setLoading(true)
    listVouchers(shopId)
      .then((all) => setVouchers(all.filter((v) => v.type === 1)))
      .finally(() => setLoading(false))
  }, [shopId])

  useEffect(() => {
    load()
  }, [load])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    setModalOpen(true)
  }

  const openEdit = (v: VoucherVO) => {
    setEditing(v)
    form.setFieldsValue({
      title: v.title,
      subTitle: v.subTitle ?? undefined,
      rules: v.rules ?? undefined,
      payYuan: centsToYuan(v.payValue),
      actualYuan: centsToYuan(v.actualValue),
      status: v.status,
    })
    setModalOpen(true)
  }

  const handleSubmit = async (values: AmountFormValues) => {
    setSubmitting(true)
    try {
    const payload = {
      shopId,
      title: values.title,
      subTitle: values.subTitle,
      rules: values.rules,
      payValue: toCents(values.payYuan),
      actualValue: toCents(values.actualYuan),
      status: values.status ?? 1,
    }
    if (editing) {
      await updateVoucher({ ...payload, id: editing.id })
      messageApi.success('券已更新')
    } else {
      await createVoucher(payload)
      messageApi.success('券已创建（C 端商户详情可见）')
    }
    setModalOpen(false)
    } finally {
      setSubmitting(false)
    }
    load()
  }

  return (
    <>
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" onClick={openCreate}>新建普通券</Button>
        <Button onClick={load}>刷新</Button>
      </Space>
      <Table<VoucherVO>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={vouchers}
        pagination={false}
        columns={[
          { title: 'ID', dataIndex: 'id', width: 180, ellipsis: true },
          { title: '标题', dataIndex: 'title' },
          { title: '支付价', dataIndex: 'payValue', width: 90, render: (v: number) => `¥${fenToYuan(v)}` },
          { title: '抵扣面值', dataIndex: 'actualValue', width: 90, render: (v: number) => `¥${fenToYuan(v)}` },
          { title: '状态', dataIndex: 'status', width: 80, render: (v: number) => (v === 1 ? '上架' : '下架') },
          {
            title: '操作',
            width: 150,
            render: (_, v) => (
              <Space>
                <Button size="small" onClick={() => openEdit(v)}>编辑</Button>
                <Popconfirm title={`删除券「${v.title}」？`} onConfirm={async () => { await deleteVoucher(v.id); messageApi.success('已删除'); load() }}>
                  <Button size="small" danger>删除</Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
      <AmountModal
        title={editing ? `编辑普通券：${editing.title}` : '新建普通券'}
        open={modalOpen}
        form={form}
        confirmLoading={submitting}
        onCancel={() => setModalOpen(false)}
        onSubmit={handleSubmit}
        withStatus
      />
    </>
  )
}

function SeckillVoucherTab({ shopId }: { shopId: string }) {
  const { message: messageApi } = App.useApp()
  const [seckills, setSeckills] = useState<SeckillVoucherVO[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [editing, setEditing] = useState<SeckillVoucherVO | null>(null)
  const [form] = Form.useForm<SeckillFormValues>()

  const load = useCallback(() => {
    setLoading(true)
    listSeckillVouchers(shopId)
      .then(setSeckills)
      .finally(() => setLoading(false))
  }, [shopId])

  useEffect(() => {
    load()
  }, [load])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ minLevel: 0, stock: 10 })
    setModalOpen(true)
  }

  const openEdit = (v: SeckillVoucherVO) => {
    setEditing(v)
    form.setFieldsValue({
      title: v.title,
      subTitle: v.subTitle ?? undefined,
      rules: v.rules ?? undefined,
      payYuan: centsToYuan(v.payValue),
      actualYuan: centsToYuan(v.actualValue),
      stock: v.stock,
      minLevel: v.minLevel,
      range: [dayjs(v.beginTime, 'YYYY-MM-DD HH:mm:ss'), dayjs(v.endTime, 'YYYY-MM-DD HH:mm:ss')],
      status: v.status,
    })
    setModalOpen(true)
  }

  const handleSubmit = async (values: SeckillFormValues) => {
    if (!values.range?.[0] || !values.range?.[1]) {
      messageApi.warning('请选择活动时间')
      return
    }
    setSubmitting(true)
    try {
    const payload = {
      shopId,
      title: values.title,
      subTitle: values.subTitle,
      rules: values.rules,
      payValue: toCents(values.payYuan),
      actualValue: toCents(values.actualYuan),
      stock: values.stock,
      minLevel: values.minLevel,
      beginTime: values.range[0].format('YYYY-MM-DD HH:mm:ss'),
      endTime: values.range[1].format('YYYY-MM-DD HH:mm:ss'),
      status: values.status ?? 1,
    }
    if (editing) {
      await updateSeckillVoucher({ ...payload, id: editing.voucherId })
      messageApi.success('活动已更新（进行中不可改库存；改时间不会重投预通知）')
    } else {
      await createSeckillVoucher(payload)
      messageApi.success('活动已创建（库存已预热；开抢前自动投预通知）')
    }
    setModalOpen(false)
    } finally {
      setSubmitting(false)
    }
    load()
  }

  return (
    <>
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" onClick={openCreate}>新建秒杀活动</Button>
        <Button onClick={load}>刷新</Button>
      </Space>
      <Table<SeckillVoucherVO>
        rowKey="voucherId"
        size="small"
        loading={loading}
        dataSource={seckills}
        pagination={false}
        columns={[
          { title: 'ID', dataIndex: 'voucherId', width: 180, ellipsis: true },
          { title: '标题', dataIndex: 'title' },
          { title: '支付价', dataIndex: 'payValue', width: 85, render: (v: number) => `¥${fenToYuan(v)}` },
          { title: '库存', dataIndex: 'stock', width: 70 },
          { title: '时间窗', width: 320, render: (_, v) => `${v.beginTime} ~ ${v.endTime}` },
          {
            title: '阶段',
            width: 85,
            render: (_, v) => {
              const tag = PHASE_TAG[phaseOf(v, Date.now())]
              return <span style={{ color: tag.color === 'default' ? undefined : tag.color }}>{tag.text}</span>
            },
          },
          {
            title: '操作',
            width: 150,
            render: (_, v) => (
              <Space>
                <Button size="small" onClick={() => openEdit(v)}>编辑</Button>
                <Popconfirm title={`删除活动「${v.title}」？`} onConfirm={async () => { await deleteSeckillVoucher(v.voucherId); messageApi.success('已删除'); load() }}>
                  <Button size="small" danger>删除</Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
      <Modal
        title={editing ? `编辑秒杀活动：${editing.title}` : '新建秒杀活动'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleSubmit}>
          <Form.Item name="title" label="活动标题" rules={[{ required: true, message: '必填' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="subTitle" label="副标题">
            <Input />
          </Form.Item>
          <Form.Item name="rules" label="规则">
            <Input />
          </Form.Item>
          <Space style={{ display: 'flex' }} align="start">
            <Form.Item
              name="payYuan"
              label="支付价（元）"
              rules={[{ required: true, message: '必填' }]}
              style={{ width: 130 }}
              extra="用户实付"
            >
              <InputNumber min={0} precision={2} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="actualYuan"
              label="抵扣面值（元）"
              rules={[{ required: true, message: '必填' }]}
              style={{ width: 130 }}
              extra="须大于支付价"
            >
              <InputNumber min={0.01} precision={2} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
          <Space style={{ display: 'flex' }} align="start">
            <Form.Item name="stock" label="库存" rules={[{ required: true, message: '必填' }]} style={{ width: 110 }} extra="进行中不可改">
              <InputNumber min={1} precision={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="minLevel" label="会员等级门槛" rules={[{ required: true }]} style={{ width: 140 }} extra="0=不限">
              <InputNumber min={0} max={9} precision={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="status" label="状态" initialValue={1} style={{ width: 100 }}>
              <Select options={[{ value: 1, label: '上架' }, { value: 2, label: '下架' }]} />
            </Form.Item>
          </Space>
          <Form.Item name="range" label="活动时间（开始 ~ 结束）" rules={[{ required: true, message: '必选' }]}>
            <DatePicker.RangePicker showTime={{ format: 'HH:mm:ss' }} format="YYYY-MM-DD HH:mm:ss" style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}

function AmountModal({
  title,
  open,
  form,
  confirmLoading,
  onCancel,
  onSubmit,
  withStatus,
}: {
  title: string
  open: boolean
  form: ReturnType<typeof Form.useForm<AmountFormValues>>[0]
  confirmLoading?: boolean
  onCancel: () => void
  onSubmit: (values: AmountFormValues) => Promise<void>
  withStatus?: boolean
}) {
  return (
    <Modal title={title} open={open} onCancel={onCancel} onOk={() => form.submit()} confirmLoading={confirmLoading} destroyOnClose>
      <Form form={form} layout="vertical" onFinish={onSubmit}>
        <Form.Item name="title" label="券标题" rules={[{ required: true, message: '必填' }]}>
          <Input />
        </Form.Item>
        <Form.Item name="subTitle" label="副标题">
          <Input />
        </Form.Item>
        <Form.Item name="rules" label="规则">
          <Input />
        </Form.Item>
        <Space style={{ display: 'flex' }} align="start">
          <Form.Item name="payYuan" label="支付价（元）" rules={[{ required: true, message: '必填' }]} style={{ width: 140 }} extra="用户实付">
            <InputNumber min={0} precision={2} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="actualYuan" label="抵扣面值（元）" rules={[{ required: true, message: '必填' }]} style={{ width: 140 }} extra="须大于支付价">
            <InputNumber min={0.01} precision={2} style={{ width: '100%' }} />
          </Form.Item>
          {withStatus && (
            <Form.Item name="status" label="状态" initialValue={1} style={{ width: 100 }}>
              <Select options={[{ value: 1, label: '上架' }, { value: 2, label: '下架' }]} />
            </Form.Item>
          )}
        </Space>
      </Form>
    </Modal>
  )
}
