import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Form, Input, List, Modal, Rate, Space, Tag, Typography, message } from 'antd'
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import {
  createKnowledge, feedbackKnowledge, fetchKnowledge, searchKnowledge,
  type KnowledgeItem, type SearchHit,
} from '../api/knowledge'

/** 知识库页（M5）：语义搜索（带采纳/无感反馈）+ 条目管理 */
export default function KnowledgePage() {
  const [items, setItems] = useState<KnowledgeItem[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [query, setQuery] = useState('')
  const [hits, setHits] = useState<SearchHit[] | null>(null)
  const [searching, setSearching] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const result = await fetchKnowledge({ page, pageSize: 10 })
      setItems(result.list)
      setTotal(result.total)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [page])

  useEffect(() => { load() }, [load])

  const search = async () => {
    if (!query.trim()) return
    setSearching(true)
    try {
      setHits(await searchKnowledge(query.trim()))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '搜索失败')
    } finally {
      setSearching(false)
    }
  }

  const feedback = async (hit: SearchHit, action: 'ADOPT' | 'DISMISS') => {
    try {
      await feedbackKnowledge(hit.itemId, action, query)
      message.success(action === 'ADOPT' ? '已反馈：这条知识有帮助' : '已反馈：将继续优化排序')
    } catch { /* 静默 */ }
  }

  return (
    <Card
      title={<Typography.Text strong>知识库</Typography.Text>}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => { setHits(null); load() }} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新增知识</Button>
        </Space>
      }
    >
      <Space.Compact style={{ width: '100%', marginBottom: 16 }}>
        <Input
          placeholder="语义搜索：如「高温密封件怎么选型」" value={query} allowClear
          onChange={(e) => setQuery(e.target.value)} onPressEnter={search}
        />
        <Button type="primary" icon={<SearchOutlined />} loading={searching} onClick={search}>搜索</Button>
      </Space.Compact>

      {hits !== null && (
        <>
          <Typography.Title level={5}>语义检索结果</Typography.Title>
          <List<SearchHit>
            dataSource={hits} rowKey="itemId" size="small" style={{ marginBottom: 24 }}
            locale={{ emptyText: '未命中知识（反馈将驱动知识盲区检测）' }}
            renderItem={(hit) => (
              <List.Item
                actions={[
                  <a key="adopt" onClick={() => feedback(hit, 'ADOPT')}>有帮助</a>,
                  <a key="dismiss" onClick={() => feedback(hit, 'DISMISS')}>无帮助</a>,
                ]}
              >
                <List.Item.Meta
                  title={<Space>{hit.title}<Tag color="orange">{(hit.score * 100).toFixed(0)}%</Tag></Space>}
                  description={hit.summary}
                />
              </List.Item>
            )}
          />
          <Typography.Title level={5}>全部条目</Typography.Title>
        </>
      )}

      <List<KnowledgeItem>
        loading={loading} dataSource={items} rowKey="id"
        pagination={{ current: page, total, pageSize: 10, onChange: setPage, size: 'small' }}
        renderItem={(item) => (
          <List.Item>
            <List.Item.Meta
              title={<Space>{item.title}
                <Tag>{item.sourceType}</Tag>
                <Rate disabled value={item.qualityScore / 20} style={{ fontSize: 12 }} />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>引用 {item.usageCount} 次</Typography.Text>
              </Space>}
              description={item.summary ?? item.content.slice(0, 100)}
            />
          </List.Item>
        )}
      />

      <Modal
        title="新增知识条目" open={createOpen} destroyOnClose
        onOk={async () => {
          const values = await form.validateFields()
          try {
            await createKnowledge(values)
            message.success('已入库并建立向量索引')
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
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="如：密封件选型规范" />
          </Form.Item>
          <Form.Item name="content" label="内容" rules={[{ required: true, message: '请输入内容' }]}>
            <Input.TextArea rows={6} placeholder="知识正文（入库后自动生成摘要与向量索引）" />
          </Form.Item>
          <Form.Item name="tags" label="标签">
            <Input placeholder="逗号分隔，如：密封,选型" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
