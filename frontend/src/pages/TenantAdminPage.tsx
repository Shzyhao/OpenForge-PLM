import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Form, Input, Modal, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { fetchTenants, onboardTenant, toggleTenant, type SysTenant } from '../api/tenant'

function randomPassword(): string {
  return 'Of@' + Math.random().toString(36).slice(2, 10) + Math.floor(Math.random() * 90 + 10)
}

/** 租户管理（十轮收口：平台级操作 + 开通流水线 = 建租户+初始管理员+绑定 ADMINS 单事务） */
export default function TenantAdminPage() {
  const [data, setData] = useState<SysTenant[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [created, setCreated] = useState<{ tenantCode: string; adminUsername: string; password: string } | null>(null)
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setData(await fetchTenants())
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const doToggle = (t: SysTenant) => {
    const enable = t.enabled !== 1
    Modal.confirm({
      title: `${enable ? '启用' : '停用'}租户 ${t.tenantName}？`,
      content: enable ? '归属用户将可重新登录。' : '停用后该租户用户将无法登录（数据保留）。',
      okButtonProps: enable ? undefined : { danger: true },
      onOk: async () => {
        try {
          await toggleTenant(t.id, enable)
          message.success('操作成功')
          await load()
        } catch (e) {
          message.error(e instanceof Error ? e.message : '操作失败')
        }
      },
    })
  }

  const submitOnboard = async () => {
    const values = await form.validateFields()
    setSaving(true)
    const password = values.adminPassword
    try {
      const result = await onboardTenant(values)
      message.success(`租户 ${result.tenant.tenantCode} 开通成功`)
      setOpen(false)
      form.resetFields()
      setCreated({ tenantCode: result.tenant.tenantCode, adminUsername: result.adminUsername, password })
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '开通失败')
    } finally {
      setSaving(false)
    }
  }

  const columns: ColumnsType<SysTenant> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '租户编码', dataIndex: 'tenantCode', width: 140 },
    { title: '租户名称', dataIndex: 'tenantName' },
    {
      title: '状态', dataIndex: 'enabled', width: 90,
      render: (v: number) => v === 1 ? <Tag color="green">启用</Tag> : <Tag color="default">停用</Tag>,
    },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
    {
      title: '操作', width: 100,
      render: (_, t) => (
        <Button size="small" danger={t.enabled === 1} onClick={() => doToggle(t)}>
          {t.enabled === 1 ? '停用' : '启用'}
        </Button>
      ),
    },
  ]

  return (
    <Card
      title={<Typography.Text strong>租户管理</Typography.Text>}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => {
            form.setFieldsValue({ tenantCode: '', tenantName: '', remark: '', adminUsername: '', adminPassword: randomPassword(), adminDisplayName: '' })
            setOpen(true)
          }}>开通租户</Button>
        </Space>
      }
    >
      <Table<SysTenant> rowKey="id" columns={columns} dataSource={data} loading={loading} size="middle" pagination={false} />

      <Modal title="开通租户（含初始管理员）" open={open} confirmLoading={saving} destroyOnClose
        onOk={submitOnboard} onCancel={() => setOpen(false)} width={520}>
        <Form form={form} layout="vertical">
          <Space size="middle" style={{ display: 'flex' }}>
            <Form.Item name="tenantCode" label="租户编码" rules={[{ required: true }, { pattern: /^[A-Za-z0-9_-]+$/, message: '字母/数字/中划线' }]}>
              <Input placeholder="如 tenantB" />
            </Form.Item>
            <Form.Item name="tenantName" label="租户名称" rules={[{ required: true }]}>
              <Input placeholder="如 B 公司" />
            </Form.Item>
          </Space>
          <Form.Item name="remark" label="备注"><Input /></Form.Item>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 12 }}>
            初始管理员将绑定 ADMINS 角色（仅本租户范围），首次登录强制修改密码。编号规则与流程定义为平台模板，开通即可用。
          </Typography.Paragraph>
          <Space size="middle" style={{ display: 'flex' }}>
            <Form.Item name="adminUsername" label="管理员用户名" rules={[{ required: true, min: 3, max: 64 }]}>
              <Input placeholder="如 tenantB_admin" />
            </Form.Item>
            <Form.Item name="adminDisplayName" label="姓名"><Input /></Form.Item>
          </Space>
          <Form.Item name="adminPassword" label="初始密码" rules={[{ required: true },
            { pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,}$/, message: '≥8位且包含字母与数字' }]}>
            <Input.Password addonAfter={<a onClick={() => form.setFieldValue('adminPassword', randomPassword())}>随机</a>} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title="租户开通成功" open={!!created} onCancel={() => setCreated(null)}
        footer={<Button type="primary" onClick={() => setCreated(null)}>我已保存</Button>}>
        {created && (
          <div>
            <p>初始管理员凭据（一次性展示，请立即转交）：</p>
            <p>租户：<Typography.Text code>{created.tenantCode}</Typography.Text></p>
            <p>账号：<Typography.Text code>{created.adminUsername}</Typography.Text></p>
            <p>密码：<Typography.Text copyable code style={{ fontSize: 16 }}>{created.password}</Typography.Text></p>
          </div>
        )}
      </Modal>
    </Card>
  )
}
