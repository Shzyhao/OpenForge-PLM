import { useState } from 'react'
import { Button, Card, InputNumber, Space, Tree, Typography, message } from 'antd'
import { SearchOutlined } from '@ant-design/icons'
import { get } from '../api/client'

interface BomNode {
  partId: number
  partNumber: string
  name: string
  quantity: number
  children: BomNode[]
}

interface AntTreeNode {
  title: React.ReactNode
  key: string
  children?: AntTreeNode[]
}

function toTree(node: BomNode, path: string): AntTreeNode {
  const key = `${path}/${node.partNumber}×${node.quantity}`
  return {
    title: <span>{node.partNumber} — {node.name} <Typography.Text type="secondary">×{node.quantity}</Typography.Text></span>,
    key,
    children: node.children.map(c => toTree(c, key)),
  }
}

/** BOM 展开页（M2）：按 BOM 编号查询并多层展开 */
export default function BomPage() {
  const [bomId, setBomId] = useState<number | null>(null)
  const [tree, setTree] = useState<AntTreeNode[]>([])
  const [loading, setLoading] = useState(false)

  const search = async () => {
    if (!bomId) return
    setLoading(true)
    try {
      const root = await get<BomNode>(`/api/v1/boms/expand?bomId=${bomId}&level=10`)
      setTree([toTree(root, '')])
    } catch (e) {
      message.error(e instanceof Error ? e.message : '查询失败')
      setTree([])
    } finally {
      setLoading(false)
    }
  }

  return (
    <Card title={<Typography.Text strong>BOM 展开</Typography.Text>}>
      <Space style={{ marginBottom: 16 }}>
        <InputNumber placeholder="BOM ID" min={1} onChange={(v) => setBomId(v)} />
        <Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={search}>展开</Button>
      </Space>
      {tree.length > 0
        ? <Tree treeData={tree} defaultExpandAll showLine />
        : <Typography.Text type="secondary">输入 BOM ID 并点击展开，查看多层结构与数量（含环检测）</Typography.Text>}
    </Card>
  )
}
