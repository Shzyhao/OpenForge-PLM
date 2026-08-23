import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Form, Input, List, Modal, Space, Tag, Typography, message } from 'antd'
import { CheckOutlined, PlusOutlined, RightOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  addProjectTask, closeProject, createProject, fetchProjectTasks, fetchProjects, moveTask,
  type Project, type ProjectTask,
} from '../api/project'

const TASK_ACTIONS: Record<string, { next: string; label: string }> = {
  TODO: { next: 'DOING', label: '开始' },
  DOING: { next: 'DONE', label: '完成' },
}

/** 项目管理页（M6）：项目列表 + 任务看板（轻量三态流转） */
export default function ProjectPage() {
  const [projects, setProjects] = useState<Project[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<Project | null>(null)
  const [tasks, setTasks] = useState<ProjectTask[]>([])
  const [createOpen, setCreateOpen] = useState(false)
  const [taskTitle, setTaskTitle] = useState('')
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const result = await fetchProjects(page)
      setProjects(result.list)
      setTotal(result.total)
      if (selected && result.list.find(p => p.id === selected.id)) {
        setTasks(await fetchProjectTasks(selected.id))
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [page, selected])

  useEffect(() => { load() }, [load])

  const openProject = async (project: Project) => {
    setSelected(project)
    setTasks(await fetchProjectTasks(project.id))
  }

  return (
    <Card
      title={<Typography.Text strong>项目管理</Typography.Text>}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建项目</Button>
        </Space>
      }
    >
      <Space align="start" style={{ width: '100%' }}>
        <List<Project>
          style={{ width: 340, marginRight: 24 }}
          loading={loading} dataSource={projects} rowKey="id" size="small"
          pagination={{ current: page, total, pageSize: 10, onChange: setPage, size: 'small' }}
          renderItem={(p) => (
            <List.Item
              actions={[
                ...(p.status === 'ACTIVE'
                  ? [<a key="open" onClick={() => openProject(p)}>打开</a>,
                     <a key="close" onClick={async () => { await closeProject(p.id); message.success('已结项'); load() }}>结项</a>]
                  : [<Tag key="closed" color="default">已结项</Tag>]),
              ]}
            >
              <List.Item.Meta
                title={<Space>{p.name}<Typography.Text code style={{ fontSize: 11 }}>{p.projectNumber}</Typography.Text></Space>}
                description={p.description ?? ''}
              />
            </List.Item>
          )}
        />

        {selected ? (
          <Card size="small" title={`任务 — ${selected.name}`} style={{ flex: 1 }} extra={
            <Space.Compact>
              <Input size="small" placeholder="新任务标题" style={{ width: 180 }}
                value={taskTitle} onChange={(e) => setTaskTitle(e.target.value)} />
              <Button size="small" type="primary" icon={<PlusOutlined />}
                onClick={async () => {
                  if (!taskTitle.trim()) return
                  await addProjectTask(selected.id, taskTitle.trim())
                  setTaskTitle('')
                  setTasks(await fetchProjectTasks(selected.id))
                }}>添加</Button>
            </Space.Compact>
          }>
            <List<ProjectTask>
              dataSource={tasks} rowKey="id" size="small"
              locale={{ emptyText: '暂无任务' }}
              renderItem={(t) => {
                const action = TASK_ACTIONS[t.status]
                return (
                  <List.Item
                    actions={action
                      ? [<Button key="move" size="small" type={t.status === 'DOING' ? 'primary' : 'default'}
                          icon={t.status === 'DOING' ? <CheckOutlined /> : <RightOutlined />}
                          onClick={async () => { await moveTask(t.id, action.next); setTasks(await fetchProjectTasks(selected.id)) }}>
                          {action.label}
                        </Button>]
                      : [<Tag key="done" color="success">已完成</Tag>]}
                  >
                    <Space>
                      <Tag color={t.status === 'TODO' ? 'default' : t.status === 'DOING' ? 'processing' : 'success'}>{t.status}</Tag>
                      {t.title}
                    </Space>
                  </List.Item>
                )
              }}
            />
          </Card>
        ) : (
          <Typography.Text type="secondary" style={{ flex: 1 }}>← 选择一个项目查看任务</Typography.Text>
        )}
      </Space>

      <Modal
        title="新建项目" open={createOpen} destroyOnClose
        onOk={async () => {
          const values = await form.validateFields()
          try {
            const project = await createProject(values)
            message.success(`创建成功：${project.projectNumber}`)
            setCreateOpen(false)
            form.resetFields()
            load()
          } catch (e) {
            message.error(e instanceof Error ? e.message : '创建失败')
          }
        }}
        onCancel={() => setCreateOpen(false)}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="项目名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如：新型密封件研发" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
