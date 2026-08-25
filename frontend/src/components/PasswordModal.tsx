import { useState } from 'react'
import { Form, Input, Modal, Typography, message } from 'antd'
import { changeMyPassword } from '../api/user'
import { clearToken } from '../api/client'

interface Props {
  open: boolean
  /** FORCE_CHANGE/EXPIRED 不可关闭；EXPIRING_SOON/主动修改可关闭 */
  mode: 'FORCE_CHANGE' | 'EXPIRING_SOON' | 'VOLUNTARY'
  daysToExpiry?: number | null
  onSuccess?: () => void
  onCancel?: () => void
}

/** 密码弹窗（方案 E3/E4/E6）：强制重置不可关闭，成功后重新登录或刷新状态 */
export default function PasswordModal({ open, mode, daysToExpiry, onSuccess, onCancel }: Props) {
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const forced = mode === 'FORCE_CHANGE' || mode === 'EXPIRING_SOON'

  const title = mode === 'FORCE_CHANGE'
    ? '密码已过期，请重置后继续使用'
    : mode === 'EXPIRING_SOON'
      ? `密码将于 ${daysToExpiry ?? '?'} 天后过期，建议立即修改`
      : '修改密码'

  const submit = async () => {
    const values = await form.validateFields()
    if (values.newPassword !== values.confirmPassword) {
      message.error('两次输入的新密码不一致')
      return
    }
    setLoading(true)
    try {
      await changeMyPassword(values.oldPassword, values.newPassword)
      message.success('密码修改成功')
      form.resetFields()
      if (mode === 'FORCE_CHANGE') {
        message.info('请使用新密码重新登录')
        clearToken()
        window.location.href = '/login'
        return
      }
      onSuccess?.()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '修改失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      title={title} open={open} onOk={submit} onCancel={() => onCancel?.()}
      closable={!forced} maskClosable={false} keyboard={!forced}
      okText={mode === 'FORCE_CHANGE' ? '重置密码' : '确认修改'} confirmLoading={loading}
    >
      <Typography.Paragraph type={mode === 'FORCE_CHANGE' ? 'danger' : 'secondary'} style={{ fontSize: 13 }}>
        {mode === 'FORCE_CHANGE'
          ? '您的密码已过期（或首次登录需修改密码），必须重置后才能继续使用系统。'
          : mode === 'EXPIRING_SOON'
            ? '密码半年有效，过期后将强制重置。可稍后处理，但建议现在修改。'
            : '密码需 ≥8 位且包含字母与数字。'}
      </Typography.Paragraph>
      <Form form={form} layout="vertical">
        <Form.Item name="oldPassword" label="当前密码" rules={[{ required: true, message: '请输入当前密码' }]}>
          <Input.Password autoComplete="current-password" />
        </Form.Item>
        <Form.Item name="newPassword" label="新密码"
          rules={[{ required: true, message: '请输入新密码' },
                  { pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,}$/, message: '≥8位且包含字母与数字' }]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item name="confirmPassword" label="确认新密码" rules={[{ required: true, message: '请再次输入' }]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
