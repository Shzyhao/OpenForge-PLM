import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Empty, Popconfirm, Space, Table, Tabs, Tag, Typography, message } from 'antd'
import { DeleteOutlined, ReloadOutlined, UndoOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  fetchTrashedBoms, fetchTrashedChanges, fetchTrashedDocs, fetchTrashedDrawings,
  fetchTrashedKnowledge, fetchTrashedParts, fetchTrashedProjects,
  purgeBom, purgeChange, purgeDoc, purgeDrawing, purgeKnowledge, purgePart, purgeProject,
  restoreBom, restoreChange, restoreDoc, restoreDrawing, restoreKnowledge, restorePart, restoreProject,
  type TrashedBom, type TrashedChange, type TrashedDoc, type TrashedDrawing,
  type TrashedKnowledge, type TrashedPart, type TrashedProject,
} from '../api/recycle'

/** 回收站（v1.23~v1.25）：七域软删行的列表/恢复/彻底删除。恢复与删除同权；purge 不可逆。 */
export default function RecycleBinPage() {
  const [parts, setParts] = useState<TrashedPart[]>([])
  const [boms, setBoms] = useState<TrashedBom[]>([])
  const [docs, setDocs] = useState<TrashedDoc[]>([])
  const [drawings, setDrawings] = useState<TrashedDrawing[]>([])
  const [knowledge, setKnowledge] = useState<TrashedKnowledge[]>([])
  const [changes, setChanges] = useState<TrashedChange[]>([])
  const [projects, setProjects] = useState<TrashedProject[]>([])
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [p, b, d, dr, k, c, pr] = await Promise.all([
        fetchTrashedParts(), fetchTrashedBoms(), fetchTrashedDocs(), fetchTrashedDrawings(),
        fetchTrashedKnowledge(), fetchTrashedChanges(), fetchTrashedProjects(),
      ])
      setParts(p)
      setBoms(b)
      setDocs(d)
      setDrawings(dr)
      setKnowledge(k)
      setChanges(c)
      setProjects(pr)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const run = async (what: string, fn: () => Promise<unknown>) => {
    try {
      await fn()
      message.success(`${what}成功`)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : `${what}失败`)
    }
  }

  const restoreBtn = (what: string, fn: () => Promise<unknown>) => (
    <Button size="small" type="primary" ghost icon={<UndoOutlined />}
      onClick={() => run(`恢复${what}`, fn)}>恢复</Button>
  )
  const purgeBtn = (label: string, fn: () => Promise<unknown>) => (
    <Popconfirm
      title={`彻底删除${label}？`}
      description="文件与关联数据将被物理删除，不可恢复。"
      okText="彻底删除" okButtonProps={{ danger: true }}
      onConfirm={() => run(`彻底删除${label}`, fn)}>
      <Button size="small" danger icon={<DeleteOutlined />}>彻底删除</Button>
    </Popconfirm>
  )

  const partColumns: ColumnsType<TrashedPart> = [
    { dataIndex: 'id', title: '#', width: 70 },
    { dataIndex: 'partNumber', title: '物料编码' },
    { dataIndex: 'name', title: '名称', ellipsis: true },
    { dataIndex: 'type', title: '类型', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    {
      width: 200,
      render: (_, r) => (
        <Space>
          {restoreBtn('物料', () => restorePart(r.id))}
          {purgeBtn(`物料 ${r.partNumber}`, () => purgePart(r.id))}
        </Space>
      ),
    },
  ]
  const bomColumns: ColumnsType<TrashedBom> = [
    { dataIndex: 'id', title: '#', width: 70 },
    { dataIndex: 'bomNumber', title: 'BOM 编号' },
    { dataIndex: 'bomType', title: '类型', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    { dataIndex: 'version', title: '版本', width: 90 },
    {
      width: 200,
      render: (_, r) => (
        <Space>
          {restoreBtn('BOM', () => restoreBom(r.id))}
          {purgeBtn(`BOM ${r.bomNumber}`, () => purgeBom(r.id))}
        </Space>
      ),
    },
  ]
  const docColumns: ColumnsType<TrashedDoc> = [
    { dataIndex: 'id', title: '#', width: 70 },
    { dataIndex: 'docNumber', title: '文档编号' },
    { dataIndex: 'title', title: '标题', ellipsis: true },
    { dataIndex: 'lifecycleState', title: '状态', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    {
      width: 200,
      render: (_, r) => (
        <Space>
          {restoreBtn('文档', () => restoreDoc(r.id))}
          {purgeBtn(`文档 ${r.docNumber}`, () => purgeDoc(r.id))}
        </Space>
      ),
    },
  ]
  const drawingColumns: ColumnsType<TrashedDrawing> = [
    { dataIndex: 'id', title: '#', width: 70 },
    { dataIndex: 'drawingNumber', title: '图号' },
    { dataIndex: 'title', title: '名称', ellipsis: true },
    { dataIndex: 'lifecycleState', title: '状态', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    {
      width: 200,
      render: (_, r) => (
        <Space>
          {restoreBtn('图纸', () => restoreDrawing(r.id))}
          {purgeBtn(`图纸 ${r.drawingNumber}`, () => purgeDrawing(r.id))}
        </Space>
      ),
    },
  ]

  const knowledgeColumns: ColumnsType<TrashedKnowledge> = [
    { dataIndex: 'id', title: '#', width: 70 },
    { dataIndex: 'title', title: '标题', ellipsis: true },
    { dataIndex: 'status', title: '状态', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    {
      width: 200,
      render: (_, r) => (
        <Space>
          {restoreBtn('知识条目', () => restoreKnowledge(r.id))}
          {purgeBtn(`知识条目 ${r.title}`, () => purgeKnowledge(r.id))}
        </Space>
      ),
    },
  ]
  const changeColumns: ColumnsType<TrashedChange> = [
    { dataIndex: 'id', title: '#', width: 70 },
    { dataIndex: 'ecrNumber', title: '变更编号' },
    { dataIndex: 'title', title: '标题', ellipsis: true },
    { dataIndex: 'state', title: '状态', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    {
      width: 200,
      render: (_, r) => (
        <Space>
          {restoreBtn('变更单', () => restoreChange(r.id))}
          {purgeBtn(`变更单 ${r.ecrNumber}`, () => purgeChange(r.id))}
        </Space>
      ),
    },
  ]
  const projectColumns: ColumnsType<TrashedProject> = [
    { dataIndex: 'id', title: '#', width: 70 },
    { dataIndex: 'projectNumber', title: '项目编号' },
    { dataIndex: 'name', title: '名称', ellipsis: true },
    { dataIndex: 'status', title: '状态', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    {
      width: 200,
      render: (_, r) => (
        <Space>
          {restoreBtn('项目', () => restoreProject(r.id))}
          {purgeBtn(`项目 ${r.projectNumber}`, () => purgeProject(r.id))}
        </Space>
      ),
    },
  ]

  const tabOf = (key: string, label: string, rows: unknown[], columns: ColumnsType<never>) => ({
    key,
    label: `${label}（${rows.length}）`,
    children: (rows.length === 0 && !loading)
      ? <Empty description={`回收站中没有${label}`} />
      : <Table rowKey="id" loading={loading} dataSource={rows as never}
        columns={columns} pagination={{ pageSize: 10, showSizeChanger: false }} />,
  })

  return (
    <Card
      title={<Typography.Text strong>回收站</Typography.Text>}
      extra={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>}
    >
      <Tabs items={[
        tabOf('parts', '物料', parts, partColumns as ColumnsType<never>),
        tabOf('boms', 'BOM', boms, bomColumns as ColumnsType<never>),
        tabOf('docs', '文档', docs, docColumns as ColumnsType<never>),
        tabOf('drawings', '图纸', drawings, drawingColumns as ColumnsType<never>),
        tabOf('knowledge', '知识', knowledge, knowledgeColumns as ColumnsType<never>),
        tabOf('changes', '变更', changes, changeColumns as ColumnsType<never>),
        tabOf('projects', '项目', projects, projectColumns as ColumnsType<never>),
      ]} />
    </Card>
  )
}
