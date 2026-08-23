import { useEffect, useRef, useState } from 'react'
import { Card, Col, Row, Spin, Typography } from 'antd'
import * as echarts from 'echarts'
import { get } from '../api/client'
import { fetchCurrentUser, type UserInfo } from '../api/user'

/** 工作台（M6 报表版）：问候 + 跨服务统计仪表盘 */
export default function Home() {
  const [user, setUser] = useState<UserInfo | null>(null)
  const [loading, setLoading] = useState(true)
  const partRef = useRef<HTMLDivElement>(null)
  const ecrRef = useRef<HTMLDivElement>(null)
  const flowRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    fetchCurrentUser().then(setUser).catch(() => undefined)
    Promise.all([
      get<Record<string, number>>('/api/v1/parts/stats'),
      get<Record<string, number>>('/api/v1/changes/stats'),
      get<Record<string, number>>('/api/v1/workflow/stats'),
    ]).then(([partStats, ecrStats, flowStats]) => {
      renderBar(partRef.current, '物料状态分布', partStats)
      renderBar(ecrRef.current, '变更申请（ECR）状态', ecrStats)
      renderBar(flowRef.current, '流程实例状态', flowStats)
    }).catch(() => undefined).finally(() => setLoading(false))
  }, [])

  const renderBar = (el: HTMLDivElement | null, title: string, data: Record<string, number>) => {
    if (!el) return
    const chart = echarts.init(el)
    chart.setOption({
      title: { text: title, left: 'center', textStyle: { fontSize: 14 } },
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 20, top: 44, bottom: 28 },
      xAxis: { type: 'category', data: Object.keys(data) },
      yAxis: { type: 'value', minInterval: 1 },
      series: [{ type: 'bar', barMaxWidth: 42, itemStyle: { color: '#F25C05' }, data: Object.values(data) }],
    })
  }

  return (
    <Row gutter={[16, 16]}>
      <Col span={24}>
        <Card>
          <Typography.Title level={4} style={{ margin: 0 }}>
            你好，{user?.displayName ?? user?.username ?? '...'} 👋
          </Typography.Title>
          <Typography.Text type="secondary">
            OpenForge PLM v1.0 —— 认证权限 / 物料 BOM / 流程引擎 / AI 中台 / 知识库已全部上线。
          </Typography.Text>
        </Card>
      </Col>
      {loading && <Col span={24} style={{ textAlign: 'center', padding: 40 }}><Spin /></Col>}
      <Col span={8}><Card><div ref={partRef} style={{ height: 260 }} /></Card></Col>
      <Col span={8}><Card><div ref={ecrRef} style={{ height: 260 }} /></Card></Col>
      <Col span={8}><Card><div ref={flowRef} style={{ height: 260 }} /></Card></Col>
    </Row>
  )
}
