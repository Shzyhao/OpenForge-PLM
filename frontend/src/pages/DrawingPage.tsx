import { useCallback, useEffect, useState } from 'react'
import {
  Button, Card, Drawer, Input, Modal, Popconfirm, Select, Space, Table, Tabs, Tag, theme,
  Typography, Upload, message,
} from 'antd'
import {
  DeleteOutlined, DownloadOutlined, EyeOutlined, LinkOutlined, PlusOutlined, ReloadOutlined,
  UploadOutlined,
} from '@ant-design/icons'
import {
  createDrawing, deleteDrawing, drawingAction, downloadDrawingFile, fetchDrawing, fetchDrawingFiles,
  fetchDrawingParts, fetchDrawingVersions, fetchDrawings, linkPart, unlinkPart, uploadDrawingFile,
  type DrawingFile, type DrawingInfo, type DrawingPartLink, type DrawingVersion,
} from '../api/drawing'
import { fetchParts, type Part } from '../api/material'
import type { PageData } from '../api/material'
import { usePerm } from '../perm/PermContext'

/** 图纸状态展示（状态机 DRAFT→REVIEWING→RELEASED→OBSOLETE） */
const STATE_META: Record<string, { color: string; label: string }> = {
  DRAFT: { color: 'default', label: '草稿' },
  REVIEWING: { color: 'processing', label: '评审中' },
  RELEASED: { color: 'green', label: '已发布' },
  OBSOLETE: { color: 'red', label: '已作废' },
}

const KIND_LABEL: Record<string, string> = { MAIN: '主文件', PREVIEW: '预览副本', ATTACHMENT: '附件' }
const ROLE_LABEL: Record<string, string> = { PART_DRAWING: '零件图', ASSEMBLY: '装配图', REFERENCE: '参考' }
const IMAGE_EXTS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg']

function previewKindOf(fileName: string): 'pdf' | 'image' | 'none' {
  const ext = fileName.split('.').pop()?.toLowerCase() ?? ''
  if (ext === 'pdf') return 'pdf'
  if (IMAGE_EXTS.includes(ext)) return 'image'
  return 'none'
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export default function DrawingPage() {
  const { token } = theme.useToken()
  const { hasPerm } = usePerm()
  const canManage = hasPerm('drawing:manage')

  const [data, setData] = useState<DrawingInfo[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [titleFilter, setTitleFilter] = useState('')
  const [stateFilter, setStateFilter] = useState<string>('')

  const [createOpen, setCreateOpen] = useState(false)
  const [createTitle, setCreateTitle] = useState('')
  const [creating, setCreating] = useState(false)

  // 详情抽屉
  const [detailOpen, setDetailOpen] = useState(false)
  const [current, setCurrent] = useState<DrawingInfo | null>(null)
  const [files, setFiles] = useState<DrawingFile[]>([])
  const [versions, setVersions] = useState<DrawingVersion[]>([])
  const [links, setLinks] = useState<DrawingPartLink[]>([])
  const [preview, setPreview] = useState<{ url: string; fileName: string; kind: 'pdf' | 'image' } | null>(null)
  const [uploadKind, setUploadKind] = useState('MAIN')
  const [uploading, setUploading] = useState(false)

  // 关联物料
  const [partOptions, setPartOptions] = useState<Part[]>([])
  const [partSearch, setPartSearch] = useState('')
  const [selectedPartId, setSelectedPartId] = useState<number | undefined>()
  const [linkRole, setLinkRole] = useState('PART_DRAWING')
  const [linking, setLinking] = useState(false)

  const load = useCallback(async (targetPage = page) => {
    setLoading(true)
    try {
      const result: PageData<DrawingInfo> = await fetchDrawings({
        page: targetPage, pageSize: 15,
        title: titleFilter || undefined, state: stateFilter || undefined,
      })
      setData(result.list)
      setTotal(result.total)
      setPage(result.page)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [page, titleFilter, stateFilter])

  useEffect(() => { void load(1) /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])

  const loadParts = useCallback(async (name: string) => {
    try {
      const result = await fetchParts({ page: 1, pageSize: 50, name: name || undefined })
      setPartOptions(result.list)
    } catch { /* 选择器加载失败静默 */ }
  }, [])

  const openDetail = async (row: DrawingInfo) => {
    setDetailOpen(true)
    setPreview(null)
    setCurrent(row)
    try {
      const [detail, fileList, versionList, linkList] = await Promise.all([
        fetchDrawing(row.id), fetchDrawingFiles(row.id),
        fetchDrawingVersions(row.id), fetchDrawingParts(row.id),
      ])
      setCurrent(detail)
      setFiles(fileList)
      setVersions(versionList)
      setLinks(linkList)
      void loadParts('')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    }
  }

  const reloadDetail = async () => {
    if (current) await openDetail({ ...current })
    await load(page)
  }

  const doCreate = async () => {
    if (!createTitle.trim()) {
      message.warning('请填写图纸标题')
      return
    }
    setCreating(true)
    try {
      const created = await createDrawing(createTitle.trim())
      message.success(`图纸已创建（${created.drawingNumber}）`)
      setCreateOpen(false)
      setCreateTitle('')
      await load(1)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败')
    } finally {
      setCreating(false)
    }
  }

  const doAction = async (action: Parameters<typeof drawingAction>[1], tip: string) => {
    if (!current) return
    try {
      const next = await drawingAction(current.id, action)
      message.success(tip)
      setCurrent(next)
      await reloadDetail()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  const doDelete = async (row: DrawingInfo) => {
    try {
      await deleteDrawing(row.id)
      message.success('已删除')
      if (current?.id === row.id) setDetailOpen(false)
      await load(page)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败')
    }
  }

  const doUpload = async (file: File) => {
    if (!current) return false
    setUploading(true)
    try {
      await uploadDrawingFile(current.id, file, uploadKind)
      message.success('文件已上传')
      setFiles(await fetchDrawingFiles(current.id))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '上传失败')
    } finally {
      setUploading(false)
    }
    return false // 阻止 antd Upload 自动上传
  }

  const openPreview = async (file: DrawingFile) => {
    if (!current) return
    const kind = previewKindOf(file.fileName)
    if (kind === 'none') {
      message.info('该文件无内嵌预览（PDF/图片副本可预览），请下载查看')
      return
    }
    try {
      const { blob } = await downloadDrawingFile(current.id, file.id)
      setPreview((prev) => {
        if (prev) URL.revokeObjectURL(prev.url)
        return { url: URL.createObjectURL(blob), fileName: file.fileName, kind }
      })
    } catch (e) {
      message.error(e instanceof Error ? e.message : '预览加载失败')
    }
  }

  const doDownload = async (file: DrawingFile) => {
    if (!current) return
    try {
      const { blob, fileName } = await downloadDrawingFile(current.id, file.id)
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

  const doLink = async () => {
    if (!current || !selectedPartId) {
      message.warning('请选择物料')
      return
    }
    const part = partOptions.find((p) => p.id === selectedPartId)
    if (!part) return
    setLinking(true)
    try {
      await linkPart(current.id, part.id, part.partNumber, linkRole)
      message.success('已关联')
      setSelectedPartId(undefined)
      setLinks(await fetchDrawingParts(current.id))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '关联失败')
    } finally {
      setLinking(false)
    }
  }

  const doUnlink = async (link: DrawingPartLink) => {
    if (!current) return
    try {
      await unlinkPart(current.id, link.partId)
      setLinks(await fetchDrawingParts(current.id))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '取消关联失败')
    }
  }

  const stateTag = (s: string) => <Tag color={STATE_META[s]?.color}>{STATE_META[s]?.label ?? s}</Tag>

  const columns = [
    { title: '编号', dataIndex: 'drawingNumber', width: 170 },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '版本', width: 80, render: (_: unknown, r: DrawingInfo) => r.versionMajor + '/' + r.versionMinor },
    { title: '状态', dataIndex: 'lifecycleState', width: 100, render: stateTag },
    { title: '检出', dataIndex: 'checkedOutBy', width: 80,
      render: (v: number | null) => (v ? <Tag color="orange">检出中</Tag> : '-') },
    {
      title: '操作', width: 180,
      render: (_: unknown, row: DrawingInfo) => (
        <Space>
          <Button size="small" onClick={() => void openDetail(row)}>详情</Button>
          {row.lifecycleState === 'DRAFT' && (
            <Popconfirm title="删除该草稿图纸？" onConfirm={() => void doDelete(row)} disabled={!canManage}>
              <Button size="small" danger icon={<DeleteOutlined />} disabled={!canManage} />
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ]

  return (
    <Card
      title={<Typography.Text strong>图纸管理</Typography.Text>}
      extra={
        <Space>
          <Button type="primary" icon={<PlusOutlined />} disabled={!canManage}
            onClick={() => setCreateOpen(true)}>新建图纸</Button>
          <Button icon={<ReloadOutlined />} onClick={() => void load(page)} />
        </Space>
      }
    >
      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search placeholder="标题搜索" allowClear style={{ width: 240 }}
          onSearch={(v) => { setTitleFilter(v); void load(1) }} />
        <Select style={{ width: 140 }} allowClear placeholder="状态"
          value={stateFilter || undefined}
          onChange={(v) => { setStateFilter(v ?? ''); void load(1) }}
          options={Object.entries(STATE_META).map(([value, m]) => ({ value, label: m.label }))} />
      </Space>
      <Table rowKey="id" size="middle" loading={loading} dataSource={data} columns={columns}
        pagination={{
          current: page, total, pageSize: 15, showSizeChanger: false,
          onChange: (p) => void load(p),
        }} />

      <Drawer
        title={current ? `图纸：${current.drawingNumber}` : '图纸'}
        width={760} open={detailOpen} onClose={() => {
          if (preview) URL.revokeObjectURL(preview.url)
          setPreview(null)
          setDetailOpen(false)
        }} destroyOnClose
      >
        {current && (
          <>
            <Space style={{ marginBottom: 12 }} wrap>
              {stateTag(current.lifecycleState)}
              <Typography.Text type="secondary">
                版本 {current.versionMajor}/{current.versionMinor}
              </Typography.Text>
              {current.checkedOutBy && <Tag color="orange">检出中</Tag>}
            </Space>
            <Space style={{ marginBottom: 16 }} wrap>
              {current.lifecycleState === 'DRAFT' && canManage && (
                <>
                  {!current.checkedOutBy && (
                    <Button size="small" onClick={() => void doAction('check-out', '已检出')}>检出</Button>
                  )}
                  {current.checkedOutBy && (
                    <Button size="small" type="primary" onClick={() => void doAction('check-in', '已检入（小版本 +1）')}>
                      检入
                    </Button>
                  )}
                  <Button size="small" disabled={!!current.checkedOutBy}
                    onClick={() => void doAction('submit', '已提交评审')}>提交评审</Button>
                </>
              )}
              {current.lifecycleState === 'REVIEWING' && canManage && (
                <>
                  <Button size="small" type="primary" onClick={() => void doAction('approve', '已发布（版本快照已固化）')}>
                    发布
                  </Button>
                  <Button size="small" onClick={() => void doAction('reject', '已驳回回草稿')}>驳回</Button>
                </>
              )}
              {current.lifecycleState === 'RELEASED' && canManage && (
                <>
                  <Button size="small" onClick={() => void doAction('revise', '已升版（进入新大版本草稿）')}>升版</Button>
                  <Popconfirm title="作废后不可恢复，确定？"
                    onConfirm={() => void doAction('obsolete', '已作废')}>
                    <Button size="small" danger>作废</Button>
                  </Popconfirm>
                </>
              )}
            </Space>
            <Tabs defaultActiveKey="files" items={[
              {
                key: 'files', label: `文件（${files.length}）`,
                children: (
                  <>
                    {canManage && current.lifecycleState === 'DRAFT' && (
                      <Space style={{ marginBottom: 12 }} wrap>
                        <Select style={{ width: 130 }} value={uploadKind}
                          onChange={setUploadKind}
                          options={[
                            { value: 'MAIN', label: '主文件' },
                            { value: 'PREVIEW', label: '预览副本' },
                            { value: 'ATTACHMENT', label: '附件' },
                          ]} />
                        <Upload beforeUpload={doUpload} showUploadList={false} disabled={uploading}>
                          <Button size="small" icon={<UploadOutlined />} loading={uploading}>上传文件</Button>
                        </Upload>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          DWG/DXF 等主文件下载查看；PDF/图片副本可内嵌预览
                        </Typography.Text>
                      </Space>
                    )}
                    <Table rowKey="id" size="small" dataSource={files} pagination={false}
                      columns={[
                        { title: '文件名', dataIndex: 'fileName', ellipsis: true },
                        { title: '类型', dataIndex: 'kind', width: 90,
                          render: (k: string) => KIND_LABEL[k] ?? k },
                        { title: '大小', dataIndex: 'fileSize', width: 90, render: formatSize },
                        { title: '操作', width: 140,
                          render: (_: unknown, f: DrawingFile) => (
                            <Space>
                              <Button size="small" icon={<EyeOutlined />}
                                disabled={previewKindOf(f.fileName) === 'none'}
                                onClick={() => void openPreview(f)}>预览</Button>
                              <Button size="small" icon={<DownloadOutlined />}
                                onClick={() => void doDownload(f)} />
                            </Space>
                          ) },
                      ]} />
                    {preview && (
                      <div style={{ marginTop: 12 }}>
                        <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>
                          预览：{preview.fileName}
                        </Typography.Text>
                        {preview.kind === 'pdf'
                          ? <iframe src={preview.url} title="preview" style={{ width: '100%', height: 420, border: `1px solid ${token.colorBorder}`, borderRadius: 8 }} />
                          : <img src={preview.url} alt={preview.fileName} style={{ maxWidth: '100%', border: `1px solid ${token.colorBorder}`, borderRadius: 8 }} />}
                      </div>
                    )}
                  </>
                ),
              },
              {
                key: 'versions', label: `版本历史（${versions.length}）`,
                children: (
                  <Table rowKey="id" size="small" dataSource={versions} pagination={false}
                    expandable={{
                      expandedRowRender: (v) => (
                        <pre style={{ margin: 0, padding: 8, fontSize: 12, background: token.colorFillQuaternary }}>
                          {v.snapshot}
                        </pre>
                      ),
                    }}
                    columns={[
                      { title: '版本', dataIndex: 'version', width: 100 },
                      { title: '状态', dataIndex: 'state', width: 100 },
                      { title: '发布时间', dataIndex: 'releasedAt', width: 170,
                        render: (v: string) => v?.replace('T', ' ').slice(0, 19) ?? '-' },
                    ]} />
                ),
              },
              {
                key: 'parts', label: `关联物料（${links.length}）`,
                children: (
                  <>
                    {canManage && current.lifecycleState !== 'OBSOLETE' && (
                      <Space style={{ marginBottom: 12 }} wrap>
                        <Select showSearch style={{ width: 260 }} placeholder="选择物料"
                          value={selectedPartId} onChange={setSelectedPartId}
                          filterOption={false}
                          onSearch={(v) => { setPartSearch(v); void loadParts(v) }}
                          options={partOptions.map((p) => ({
                            value: p.id, label: `${p.partNumber}（${p.name}）`,
                          }))} />
                        <Select style={{ width: 120 }} value={linkRole} onChange={setLinkRole}
                          options={Object.entries(ROLE_LABEL).map(([value, label]) => ({ value, label }))} />
                        <Button size="small" icon={<LinkOutlined />} loading={linking}
                          onClick={() => void doLink()}>关联</Button>
                      </Space>
                    )}
                    <Table rowKey="id" size="small" dataSource={links} pagination={false}
                      columns={[
                        { title: '物料编码', dataIndex: 'partNumber' },
                        { title: '角色', dataIndex: 'role', width: 110,
                          render: (r: string) => ROLE_LABEL[r] ?? r },
                        ...(canManage && current.lifecycleState !== 'OBSOLETE' ? [{
                          title: '操作', width: 80,
                          render: (_: unknown, l: DrawingPartLink) => (
                            <Button size="small" danger onClick={() => void doUnlink(l)}>移除</Button>
                          ),
                        }] : []),
                      ]} />
                    {partSearch === '' && links.length === 0 && (
                      <DrawingByPartHint />
                    )}
                  </>
                ),
              },
            ]} />
          </>
        )}
      </Drawer>

      <Modal title="新建图纸" open={createOpen} onCancel={() => setCreateOpen(false)}
        onOk={doCreate} confirmLoading={creating} destroyOnClose>
        <Input placeholder="图纸标题，如 法兰盘零件图" value={createTitle}
          onChange={(e) => setCreateTitle(e.target.value)}
          onPressEnter={doCreate} />
        <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
          编号自动生成（DW-日期-流水）；创建后上传主文件即可提交评审发布。
        </Typography.Text>
      </Modal>
    </Card>
  )
}

/** 空关联提示（按物料反查入口说明） */
function DrawingByPartHint() {
  return (
    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
      尚未关联物料。关联后可在物料维度反查图纸（GET /api/v1/drawings/by-part/{'{'}partId{'}'}）。
    </Typography.Text>
  )
}
