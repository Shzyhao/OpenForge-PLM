import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Empty, Popconfirm, Table, Tabs, Tag, Typography, message } from 'antd'
import { ReloadOutlined, UndoOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  fetchTrashedDrawings, fetchTrashedParts, restoreDrawing, restorePart,
  type TrashedDrawing, type TrashedPart,
} from '../api/recycle'

/** 回收站（v1.23）：物料/图纸软删行的列表与恢复。恢复与删除同权（part:delete / drawing:manage）。 */
export default function RecycleBinPage() {
  const [parts, setParts] = useState<TrashedPart[]>([])
  const [drawings, setDrawings] = useState<TrashedDrawing[]>([])
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [p, d] = await Promise.all([fetchTrashedParts(), fetchTrashedDrawings()])
      setParts(p)
      setDrawings(d)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const doRestorePart = async (id: number) => {
    try {
      await restorePart(id)
      message.success('物料已恢复')
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '恢复失败')
    }
  }

  const doRestoreDrawing = async (id: number) => {
    try {
      await restoreDrawing(id)
      message.success('图纸已恢复')
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '恢复失败')
    }
  }

  const partColumns: ColumnsType<TrashedPart> = [
    { dataIndex: 'id', title: '#', width: 70 },
    { dataIndex: 'partNumber', title: '物料编码' },
    { dataIndex: 'name', title: '名称', ellipsis: true },
    { dataIndex: 'type', title: '类型', width: 120, render: (v: string) => <Tag>{v}</Tag> },
    { dataIndex: 'lifecycleState', title: '状态', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    {
      width: 90,
      render: (_, r) => (
        <Popconfirm title={`确认恢复物料 ${r.partNumber}？`} onConfirm={() => doRestorePart(r.id)}>
          <Button size="small" type="primary" ghost icon={<UndoOutlined />}>恢复</Button>
        </Popconfirm>
      ),
    },
  ]

  const drawingColumns: ColumnsType<TrashedDrawing> = [
    { dataIndex: 'id', title: '#', width: 70 },
    { dataIndex: 'drawingNumber', title: '图号' },
    { dataIndex: 'title', title: '名称', ellipsis: true },
    { dataIndex: 'lifecycleState', title: '状态', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    {
      width: 90,
      render: (_, r) => (
        <Popconfirm title={`确认恢复图纸 ${r.drawingNumber}？`} onConfirm={() => doRestoreDrawing(r.id)}>
          <Button size="small" type="primary" ghost icon={<UndoOutlined />}>恢复</Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <Card
      title={<Typography.Text strong>回收站</Typography.Text>}
      extra={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>}
    >
      <Tabs items={[
        {
          key: 'parts', label: `物料（${parts.length}）`,
          children: (parts.length === 0 && !loading)
            ? <Empty description="回收站中没有物料" />
            : <Table<TrashedPart> rowKey="id" loading={loading} dataSource={parts}
              columns={partColumns} pagination={{ pageSize: 10, showSizeChanger: false }} />,
        },
        {
          key: 'drawings', label: `图纸（${drawings.length}）`,
          children: (drawings.length === 0 && !loading)
            ? <Empty description="回收站中没有图纸" />
            : <Table<TrashedDrawing> rowKey="id" loading={loading} dataSource={drawings}
              columns={drawingColumns} pagination={{ pageSize: 10, showSizeChanger: false }} />,
        },
      ]} />
    </Card>
  )
}
