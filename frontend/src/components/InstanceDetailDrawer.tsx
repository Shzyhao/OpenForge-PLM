import { useEffect, useState } from 'react'
import { Descriptions, Drawer, Tag, Typography, message } from 'antd'
import { fetchInstance, INSTANCE_STATE_LABELS, type WorkflowInstance } from '../api/workflow'

/**
 * 流程实例详情抽屉（v1.28 P3#19）：按 instanceId 拉取实时实例——我的待办/变更详情两处复用。
 * 受控组件：open 由父级持有，instanceId 变化即拉取；服务不可达时 Drawer 内降级提示。
 */
export default function InstanceDetailDrawer({ instanceId, open, onClose }: {
  instanceId: number | null
  open: boolean
  onClose: () => void
}) {
  const [instance, setInstance] = useState<WorkflowInstance | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (!open || !instanceId) return
    setInstance(null)
    setFailed(false)
    fetchInstance(instanceId)
      .then(setInstance)
      .catch((e: unknown) => {
        setFailed(true)
        message.warning(e instanceof Error ? `流程实例加载失败：${e.message}` : '流程实例加载失败')
      })
  }, [open, instanceId])

  return (
    <Drawer title={`流程实例 #${instanceId ?? '-'}`} width={480} open={open} onClose={onClose}>
      {failed && <Typography.Text type="warning">流程服务不可达，稍后重试。</Typography.Text>}
      {instance && (
        <Descriptions size="small" column={1} bordered>
          <Descriptions.Item label="流程定义">{instance.defKey}（v{instance.defVersion}）</Descriptions.Item>
          <Descriptions.Item label="业务对象">{instance.bizType} / #{instance.bizId ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="状态">
            {(() => {
              const meta = INSTANCE_STATE_LABELS[instance.state]
              return meta ? <Tag color={meta.color}>{meta.label}</Tag> : instance.state
            })()}
          </Descriptions.Item>
          <Descriptions.Item label="当前节点">{instance.currentNode ?? '-'}</Descriptions.Item>
        </Descriptions>
      )}
      {!instance && !failed && <Typography.Text type="secondary">加载中…</Typography.Text>}
    </Drawer>
  )
}
