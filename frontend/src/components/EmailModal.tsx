import { useEffect, useState } from 'react'
import { Form, Input, Modal, Typography, message } from 'antd'
import { updateMyEmail } from '../api/user'

interface Props {
  open: boolean
  currentEmail: string | null
  onSuccess?: () => void
  onCancel?: () => void
}

/** 联系邮箱自助设置弹窗（邮箱改造 2026-11）：每个用户可自行维护自己的邮箱 */
export default function EmailModal({ open, currentEmail, onSuccess, onCancel }: Props) {
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (open) form.setFieldsValue({ email: currentEmail ?? '' })
  }, [open, currentEmail, form])

  const submit = async () => {
    let values: { email: string }
    try {
      values = await form.validateFields()
    } catch {
      return // 校验失败：antd 已在表单项下展示错误，无需额外处理
    }
    setLoading(true)
    try {
      await updateMyEmail(values.email.trim())
      message.success('邮箱已更新')
      form.resetFields()
      onSuccess?.()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '修改失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      title="设置联系邮箱" open={open} onOk={submit} onCancel={() => onCancel?.()}
      okText="保存" confirmLoading={loading}
    >
      <Typography.Paragraph type="secondary" style={{ fontSize: 13 }}>
        联系邮箱由本人自助维护，用于账号资料展示与后续可能的系统通知。
      </Typography.Paragraph>
      <Form form={form} layout="vertical">
        <Form.Item name="email" label="邮箱"
          rules={[{ required: true, message: '请输入邮箱' },
                  { type: 'email', message: '邮箱格式不正确' }]}>
          <Input placeholder="name@example.com" autoComplete="email" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
