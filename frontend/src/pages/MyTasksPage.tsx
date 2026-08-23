import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Empty, List, Space, Tag, Typography, message } from 'antd'
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons'
import { fetchMyTasks, actTask, type WorkflowTask } from '../api/workflow'

/** 我的待办（任务中心，M3）：审批办理 */
export default function MyTasksPage() {
  const [tasks, setTasks] = useState<WorkflowTask[]>([])
  const [loading, setLoading] = useState(false)
  const [actingId, setActingId] = useState<number | null>(null)

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

  const act = async (task: WorkflowTask, action: 'APPROVE' | 'REJECT') => {
    const comment = action === 'APPROVE'
      ? window.prompt('审批意见（可留空）', '同意') ?? ''
      : window.prompt('驳回原因', '') ?? ''
    if (action === 'REJECT' && !comment) return
    setActingId(task.id)
    try {
      const instance = await actTask(task.id, action, comment)
      message.success(action === 'APPROVE' ? '已通过' : '已驳回')
      if (instance.state === 'COMPLETED') message.info('流程已完成')
      if (instance.state === 'REJECTED') message.warning('流程被驳回终止')
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    } finally {
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
                    onClick={() => act(task, 'APPROVE')}>通过</Button>,
                  <Button key="reject" size="small" danger icon={<CloseOutlined />}
                    onClick={() => act(task, 'REJECT')}>驳回</Button>,
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
    </Card>
  )
}
