import { Button, Card, Collapse, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, App } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { createShop, deleteShop, fetchShopTypes, pageShops, updateShop, createShopType, deleteShopType, updateShopType } from '../../api/shop'
import type { ShopTypeVO, ShopVO } from '../../types/api'
import { fenToYuan } from '../../utils/format'

interface ShopFormValues {
  id?: string
  name: string
  typeId: string
  images?: string
  area?: string
  address: string
  longitude: number
  latitude: number
  avgPrice?: number
  openHours?: string
}

export default function AdminShopsPage() {
  const { message: messageApi } = App.useApp()
  const [shops, setShops] = useState<ShopVO[]>([])
  const [types, setTypes] = useState<ShopTypeVO[]>([])
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [size] = useState(10)
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [typesLoading, setTypesLoading] = useState(false)
  const [editing, setEditing] = useState<ShopVO | null>(null)
  const [form] = Form.useForm<ShopFormValues>()

  const load = useCallback(() => {
    setLoading(true)
    setTypesLoading(true)
    Promise.all([pageShops(null, page, size), fetchShopTypes()])
      .then(([p, t]) => {
        setShops(p.records)
        setTotal(p.total)
        setTypes(t)
      })
      .finally(() => {
        setLoading(false)
        setTypesLoading(false)
      })
  }, [page, size])

  useEffect(() => {
    load()
  }, [load])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    setModalOpen(true)
  }

  const openEdit = (shop: ShopVO) => {
    setEditing(shop)
    form.setFieldsValue({
      id: shop.id,
      name: shop.name,
      typeId: shop.typeId,
      images: shop.images,
      area: shop.area,
      address: shop.address,
      longitude: shop.longitude,
      latitude: shop.latitude,
      avgPrice: shop.avgPrice,
      openHours: shop.openHours,
    })
    setModalOpen(true)
  }

  const handleSubmit = async (values: ShopFormValues) => {
    setSubmitting(true)
    try {
    if (editing) {
      await updateShop({ ...values, id: editing.id } as Record<string, unknown>)
      messageApi.success('商户已更新（缓存已失效，C 端刷新可见）')
    } else {
      await createShop(values as unknown as Record<string, unknown>)
      messageApi.success('商户已创建')
    }
    setModalOpen(false)
    } finally {
      setSubmitting(false)
    }
    load()
  }

  const handleDelete = async (shop: ShopVO) => {
    await deleteShop(shop.id)
    messageApi.success('商户已删除')
    load()
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card
        title="商户管理"
        extra={<Button type="primary" onClick={openCreate}>新建商户</Button>}
      >
        <Table<ShopVO>
          rowKey="id"
          size="small"
          loading={loading}
          dataSource={shops}
          pagination={{ current: page, pageSize: size, total, onChange: setPage, showTotal: (t) => `共 ${t} 家` }}
          columns={[
            { title: 'ID', dataIndex: 'id', width: 180, ellipsis: true },
            { title: '名称', dataIndex: 'name' },
            { title: '区域', dataIndex: 'area', width: 120 },
            { title: '地址', dataIndex: 'address', ellipsis: true },
            { title: '人均', dataIndex: 'avgPrice', width: 90, render: (v: number) => `¥${fenToYuan(v)}` },
            { title: '营业时间', dataIndex: 'openHours', width: 120 },
            {
              title: '操作',
              width: 150,
              render: (_, shop) => (
                <Space>
                  <Button size="small" onClick={() => openEdit(shop)}>编辑</Button>
                  <Popconfirm title={`删除商户「${shop.name}」？`} onConfirm={() => handleDelete(shop)}>
                    <Button size="small" danger>删除</Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Collapse
        items={[
          {
            key: 'types',
            label: '商户类型管理',
            children: <TypePanel types={types} loading={typesLoading} onChanged={load} />,
          },
        ]}
      />

      <Modal
        title={editing ? `编辑商户：${editing.name}` : '新建商户'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleSubmit}>
          <Form.Item name="name" label="商户名称" rules={[{ required: true, message: '必填' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="typeId" label="商户类型" rules={[{ required: true, message: '必填' }]}>
            <Select options={types.map((t) => ({ value: t.id, label: t.name }))} />
          </Form.Item>
          <Form.Item name="address" label="地址" rules={[{ required: true, message: '必填' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="area" label="区域">
            <Input />
          </Form.Item>
          <Space style={{ display: 'flex' }} align="start">
            <Form.Item name="longitude" label="经度" rules={[{ required: true, message: '必填' }]} style={{ width: 160 }}>
              <InputNumber min={-180} max={180} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="latitude" label="纬度" rules={[{ required: true, message: '必填' }]} style={{ width: 160 }}>
              <InputNumber min={-90} max={90} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="avgPrice" label="人均（分）" style={{ width: 140 }}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
          <Form.Item name="openHours" label="营业时间">
            <Input placeholder="如 10:00-22:00" />
          </Form.Item>
          <Form.Item name="images" label="图片路径（逗号分隔）">
            <Input placeholder="/images/shop/x.jpg" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}

function TypePanel({ types, loading, onChanged }: { types: ShopTypeVO[]; loading?: boolean; onChanged: () => void }) {
  const { message: messageApi } = App.useApp()
  const [form] = Form.useForm<{ name: string; sort?: number }>()
  const [editing, setEditing] = useState<ShopTypeVO | null>(null)

  const handleSubmit = async (values: { name: string; sort?: number }) => {
    if (editing) {
      await updateShopType({ id: editing.id, ...values })
      messageApi.success('类型已更新')
    } else {
      await createShopType(values)
      messageApi.success('类型已创建')
    }
    setEditing(null)
    form.resetFields()
    onChanged()
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Table<ShopTypeVO>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={types}
        pagination={false}
        columns={[
          { title: 'ID', dataIndex: 'id', width: 100 },
          { title: '名称', dataIndex: 'name' },
          { title: '排序', dataIndex: 'sort', width: 80 },
          {
            title: '操作',
            width: 150,
            render: (_, t) => (
              <Space>
                <Button size="small" onClick={() => { setEditing(t); form.setFieldsValue({ name: t.name, sort: t.sort }) }}>
                  编辑
                </Button>
                <Popconfirm
                  title={`删除类型「${t.name}」？`}
                  onConfirm={async () => {
                    await deleteShopType(t.id)
                    messageApi.success('类型已删除')
                    onChanged()
                  }}
                >
                  <Button size="small" danger>删除</Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
      <Form form={form} layout="inline" onFinish={handleSubmit} initialValues={{ sort: 0 }}>
        <Form.Item name="name" rules={[{ required: true, message: '名称必填' }]}>
          <Input placeholder={editing ? `编辑：${editing.name}` : '新类型名称'} style={{ width: 180 }} />
        </Form.Item>
        <Form.Item name="sort" initialValue={0}>
          <InputNumber placeholder="排序" min={0} style={{ width: 90 }} />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit">{editing ? '保存' : '新增'}</Button>
            {editing && <Button onClick={() => { setEditing(null); form.resetFields() }}>取消</Button>}
          </Space>
        </Form.Item>
      </Form>
    </Space>
  )
}
