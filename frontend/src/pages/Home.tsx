import { useEffect, useRef, useState } from 'react'
import { Card, Col, Row, Spin, Statistic, Typography } from 'antd'
import * as echarts from 'echarts'
import { get } from '../api/client'
import { fetchCurrentUser, type UserInfo } from '../api/user'
import { BRAND, useThemeMode } from '../theme/ThemeMode'

/** 工作台（仪表盘版）：问候 + 统计卡 + 跨服务状态图表，暗色自适应 */
export default function Home() {
  const [user, setUser] = useState<UserInfo | null>(null)
  const [loading, setLoading] = useState(true)
  const [totals, setTotals] = useState<{ parts: number; changes: number; flows: number } | null>(null)
  const partRef = useRef<HTMLDivElement>(null)
  const ecrRef = useRef<HTMLDivElement>(null)
  const flowRef = useRef<HTMLDivElement>(null)
  const { mode } = useThemeMode()

  useEffect(() => {
    fetchCurrentUser().then(setUser).catch(() => undefined)
    Promise.all([
      get<Record<string, number>>('/api/v1/parts/stats'),
      get<Record<string, number>>('/api/v1/changes/stats'),
      get<Record<string, number>>('/api/v1/workflow/stats'),
    ]).then(([partStats, ecrStats, flowStats]) => {
      setTotals({
        parts: Object.values(partStats).reduce((a, b) => a + b, 0),
        changes: Object.values(ecrStats).reduce((a, b) => a + b, 0),
        flows: Object.values(flowStats).reduce((a, b) => a + b, 0),
      })
      renderBar(partRef.current, '物料状态分布', partStats, 0)
      renderBar(ecrRef.current, '变更申请（ECR）状态', ecrStats, 1)
      renderBar(flowRef.current, '流程实例状态', flowStats, 2)
    }).catch(() => undefined).finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode])

  const renderBar = (el: HTMLDivElement | null, title: string, data: Record<string, number>, colorIndex: number) => {
    if (!el) return
    echarts.getInstanceByDom(el)?.dispose()
    const axisColor = mode === 'dark' ? 'rgba(255,255,255,0.65)' : '#4A5568'
    const splitColor = mode === 'dark' ? 'rgba(255,255,255,0.10)' : 'rgba(0,0,0,0.06)'
    const chart = echarts.init(el)
    chart.setOption({
      title: { text: title, left: 'center', textStyle: { fontSize: 14, color: axisColor } },
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 20, top: 44, bottom: 28 },
      color: [BRAND.chartPalette[colorIndex % BRAND.chartPalette.length]],
      textStyle: { color: axisColor },
      xAxis: { type: 'category', data: Object.keys(data), axisLine: { lineStyle: { color: splitColor } } },
      yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: splitColor } } },
      series: [{ type: 'bar', barMaxWidth: 42, itemStyle: { borderRadius: [4, 4, 0, 0] }, data: Object.values(data) }],
    })
  }

  const greeting = (() => {
    const h = new Date().getHours()
    if (h < 6) return '夜深了'
    if (h < 12) return '早上好'
    if (h < 14) return '中午好'
    if (h < 18) return '下午好'
    return '晚上好'
  })()

  return (
    <Spin spinning={loading}>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        {greeting}，{user?.displayName || user?.username || ''}
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
        今天是 {new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })}
      </Typography.Paragraph>

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} sm={8}>
          <Card><Statistic title="物料总数" value={totals?.parts ?? 0} /></Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card><Statistic title="变更申请（ECR）" value={totals?.changes ?? 0} /></Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card><Statistic title="流程实例" value={totals?.flows ?? 0} /></Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={8}><Card><div ref={partRef} style={{ height: 260 }} /></Card></Col>
        <Col xs={24} lg={8}><Card><div ref={ecrRef} style={{ height: 260 }} /></Card></Col>
        <Col xs={24} lg={8}><Card><div ref={flowRef} style={{ height: 260 }} /></Card></Col>
      </Row>
    </Spin>
  )
}
