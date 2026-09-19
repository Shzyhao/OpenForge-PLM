import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Drawer, Form, Input, Modal, Select, Space, Table, Tag, Typography, Upload, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { DownloadOutlined, EyeOutlined, PlusOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons'
import {
  checkIn, checkOut, createDoc, downloadDocFile, fetchDocFiles, fetchDocs, uploadDocFile,
  type DocFile, type DocInfo,
} from '../api/doc'

const DOC_TYPE_LABELS: Record<string, string> = {
  GENERAL: '通用文档',
  SPEC: '规格书',
  REPORT: '报告',
  DRAWING: '图纸',
}

const IMAGE_EXTS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp']

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 文档管理页（M2）：列表 + 新建 + 检入检出；十轮补文件上传/下载/内嵌预览（此前上传后无法取回） */
export default function DocPage() {
  const [data, setData] = useState<DocInfo[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [titleFilter, setTitleFilter] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [form] = Form.useForm()

  const [filesDoc, setFilesDoc] = useState<DocInfo | null>(null)
  const [files, setFiles] = useState<DocFile[]>([])
  const [filesLoading, setFilesLoading] = useState(false)
  const [pickedFile, setPickedFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<{ url: string; fileName: string; ext: string } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const result = await fetchDocs({ page, pageSize: 10, title: titleFilter })
      setData(result.list)
      setTotal(result.total)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [page, titleFilter])

  useEffect(() => { load() }, [load])

  const act = async (id: number, action: 'check-out' | 'check-in') => {
    try {
      const doc = await (action === 'check-out' ? checkOut(id) : checkIn(id))
      message.success(`操作成功，当前版本 ${doc.versionMajor}/${doc.versionMinor}`)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  const openFiles = async (doc: DocInfo) => {
    setFilesDoc(doc)
    setPickedFile(null)
    setFilesLoading(true)
    try {
      setFiles(await fetchDocFiles(doc.id))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '文件列表加载失败')
    } finally {
      setFilesLoading(false)
    }
  }

  const doUpload = async () => {
    if (!filesDoc || !pickedFile) {
      message.warning('请先选择文件')
      return
    }
    try {
      await uploadDocFile(filesDoc.id, pickedFile)
      message.success('已上传')
      setPickedFile(null)
      setFiles(await fetchDocFiles(filesDoc.id))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '上传失败')
    }
  }

  const extOf = (name: string) => (name.split('.').pop() ?? '').toLowerCase()

  const doPreview = async (file: DocFile) => {
    if (!filesDoc) return
    const ext = extOf(file.fileName)
    if (ext !== 'pdf' && !IMAGE_EXTS.includes(ext)) {
      message.info('该类型不支持内嵌预览，请下载查看')
      return
    }
    try {
      const { blob } = await downloadDocFile(filesDoc.id, file.id)
      setPreview((prev) => {
        if (prev) URL.revokeObjectURL(prev.url)
        return { url: URL.createObjectURL(blob), fileName: file.fileName, ext }
      })
    } catch (e) {
      message.error(e instanceof Error ? e.message : '预览加载失败')
    }
  }

  const doDownload = async (file: DocFile) => {
    if (!filesDoc) return
    try {
      const { blob, fileName } = await downloadDocFile(filesDoc.id, file.id)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = fileName
      a.click()
      URL.revokeObjectURL(url)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '下载失败')
    }
  }

  const closePreview = () => {
    setPreview((prev) => {
      if (prev) URL.revokeObjectURL(prev.url)
      return null
    })
  }

  const columns: ColumnsType<DocInfo> = [
    { title: '文档编号', dataIndex: 'docNumber', width: 150 },
    { title: '标题', dataIndex: 'title' },
    { title: '类型', dataIndex: 'docType', width: 100, render: (t: string) => DOC_TYPE_LABELS[t] ?? t },
    {
      title: '版本', width: 70,
      render: (_, d) => `${d.versionMajor}/${d.versionMinor}`,
    },
    {
      title: '状态', dataIndex: 'checkedOutBy', width: 110,
      render: (by: number | null) => by !== null ? <Tag color="warning">已检出({by})</Tag> : <Tag color="green">可编辑</Tag>,
    },
    {
      title: '操作', width: 200,
      render: (_, doc) => (
        <Space size="small">
          {doc.checkedOutBy === null
            ? <Button size="small" onClick={() => act(doc.id, 'check-out')}>检出</Button>
            : <Button size="small" type="primary" onClick={() => act(doc.id, 'check-in')}>检入</Button>}
          <Button size="small" onClick={() => openFiles(doc)}>文件</Button>
        </Space>
      ),
    },
  ]

  return (
    <Card
      title={<Typography.Text strong>文档管理</Typography.Text>}
      extra={
        <Space>
          <Input.Search
            placeholder="按标题搜索" allowClear style={{ width: 200 }}
            onSearch={(v) => { setPage(1); setTitleFilter(v) }}
          />
          <Button icon={<ReloadOutlined />} onClick={load} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建文档</Button>
        </Space>
      }
    >
      <Table<DocInfo>
        rowKey="id" columns={columns} dataSource={data} loading={loading}
        pagination={{ current: page, total, pageSize: 10, onChange: setPage, showTotal: (t) => `共 ${t} 条` }}
      />
      <Modal
        title="新建文档" open={createOpen} destroyOnClose
        onOk={async () => {
          const values = await form.validateFields()
          try {
            const doc = await createDoc(values.title, values.docType)
            message.success(`创建成功：${doc.docNumber}`)
            setCreateOpen(false)
            form.resetFields()
            load()
          } catch (e) {
            message.error(e instanceof Error ? e.message : '创建失败')
          }
        }}
        onCancel={() => setCreateOpen(false)}
      >
        <Form form={form} layout="vertical" initialValues={{ docType: 'GENERAL' }}>
          <Form.Item name="title" label="文档标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="如：法兰盘设计规格书" />
          </Form.Item>
          <Form.Item name="docType" label="文档类型">
            <Select options={Object.entries(DOC_TYPE_LABELS).map(([v, l]) => ({ value: v, label: l }))} />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={filesDoc ? `文件 · ${filesDoc.title}` : '文件'} width={620} open={!!filesDoc}
        onClose={() => setFilesDoc(null)}
      >
        <Space style={{ marginBottom: 12 }} wrap>
          <Upload maxCount={1} beforeUpload={(file) => { setPickedFile(file); return false }}
            onRemove={() => setPickedFile(null)}
            fileList={pickedFile ? [{ uid: 'picked', name: pickedFile.name, status: 'done' }] : []}>
            <Button icon={<UploadOutlined />}>选择文件</Button>
          </Upload>
          <Button type="primary" disabled={!pickedFile} onClick={doUpload}>上传</Button>
        </Space>
        <Table<DocFile> rowKey="id" dataSource={files} loading={filesLoading} size="small" pagination={false}
          columns={[
            { title: '文件名', dataIndex: 'fileName', ellipsis: true },
            { title: '大小', dataIndex: 'fileSize', width: 90, render: (s: number) => formatSize(s) },
            { title: 'SHA256', dataIndex: 'sha256', width: 90, ellipsis: true, render: (s: string) => <Typography.Text code style={{ fontSize: 10 }}>{s.slice(0, 8)}…</Typography.Text> },
            {
              title: '操作', width: 130,
              render: (_, f) => (
                <Space size="small">
                  <Button size="small" icon={<EyeOutlined />} onClick={() => doPreview(f)}>预览</Button>
                  <Button size="small" icon={<DownloadOutlined />} onClick={() => doDownload(f)} />
                </Space>
              ),
            },
          ]} />
      </Drawer>

      <Modal title={preview?.fileName} open={!!preview} width={760} footer={null} onCancel={closePreview} destroyOnClose>
        {preview?.ext === 'pdf'
          ? <iframe src={preview.url} title={preview.fileName} style={{ width: '100%', height: 560, border: 'none' }} />
          : <img src={preview?.url} alt={preview?.fileName} style={{ maxWidth: '100%' }} />}
      </Modal>
    </Card>
  )
}
