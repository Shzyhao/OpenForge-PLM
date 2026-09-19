import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Empty, Input, List, Modal, Space, Tag, Typography, message } from 'antd'
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons'
import { fetchMyTasks, actTask, type WorkflowTask } from '../api/workflow'

/** 我的待办（任务中心，M3）：审批办理。
 *  审批意见用 antd Modal 收集（v1.20.0 七轮体检修复：原 window.prompt 原生对话框
 *  与 SPA 体验割裂、阻塞 JS 线程且自动化环境不可达）。 */
export default function MyTasksPage() {
  const [tasks, setTasks] = useState<WorkflowTask[]>([])
  const [loading, setLoading] = useState(false)
  const [actingId, setActingId] = useState<number | null>(null)
  const [pending, setPending] = useState<{ task: WorkflowTask; action: 'APPROVE' | 'REJECT' } | null>(null)
  const [comment, setComment] = useState('')
  const [acting, setActing] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setTasks(await fetchMyTasks())
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const ask = (task: WorkflowTask, action: 'APPROVE' | 'REJECT') => {
    setComment(action === 'APPROVE' ? '同意' : '')
    setPending({ task, action })
  }

  const act = async () => {
    if (!pending) return
    if (pending.action === 'REJECT' && !comment.trim()) {
      message.warning('驳回原因必填')
      return
    }
    setActing(true)
    setActingId(pending.task.id)
    try {
      const instance = await actTask(pending.task.id, pending.action, comment)
      message.success(pending.action === 'APPROVE' ? '已通过' : '已驳回')
      if (instance.state === 'COMPLETED') message.info('流程已完成')
      if (instance.state === 'REJECTED') message.warning('流程被驳回终止')
      setPending(null)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    } finally {
      setActing(false)
      setActingId(null)
    }
  }

  return (
    <Card
      title={<Typography.Text strong>我的待办</Typography.Text>}
      extra={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>}
    >
      {tasks.length === 0 && !loading
        ? <Empty description="暂无待办任务" />
        : (
          <List<WorkflowTask>
            loading={loading} dataSource={tasks} rowKey="id"
            renderItem={(task) => (
              <List.Item
                actions={[
                  <Button key="approve" size="small" type="primary" icon={<CheckOutlined />}
                    loading={actingId === task.id}
                    onClick={() => ask(task, 'APPROVE')}>通过</Button>,
                  <Button key="reject" size="small" danger icon={<CloseOutlined />}
                    onClick={() => ask(task, 'REJECT')}>驳回</Button>,
                ]}
              >
                <List.Item.Meta
                  title={<Space>{task.nodeName ?? task.nodeId}
                    {task.candidateRole && <Tag color="blue">{task.candidateRole}</Tag>}
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>#{task.id}</Typography.Text>
                  </Space>}
                  description={`流程实例 ${task.instanceId}`}
                />
              </List.Item>
            )}
          />
        )}
      <Modal
        title={pending ? `${pending.action === 'APPROVE' ? '通过' : '驳回'}：${pending.task.nodeName ?? pending.task.nodeId}（#${pending.task.id}）` : ''}
        open={!!pending}
        onCancel={() => setPending(null)}
        onOk={act}
        confirmLoading={acting}
        okText={pending?.action === 'APPROVE' ? '通 过' : '驳 回'}
        okButtonProps={pending?.action === 'REJECT' ? { danger: true } : undefined}
        destroyOnClose
      >
        {pending?.action === 'REJECT' && (
          <Typography.Text type="danger" style={{ display: 'block', marginBottom: 8, fontSize: 12 }}>
            驳回后流程终止，原因必填。
          </Typography.Text>
        )}
        <Input.TextArea
          rows={3}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          placeholder={pending?.action === 'APPROVE' ? '审批意见（可留空）' : '驳回原因（必填）'}
        />
      </Modal>
    </Card>
  )
}
