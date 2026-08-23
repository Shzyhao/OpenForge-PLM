import { Card, Col, Row, Statistic, Tag, Typography } from 'antd'
import { fetchCurrentUser, type UserInfo } from '../api/user'
import { useEffect, useState } from 'react'

/** 工作台首页（M1 版本：登录验证 + 系统概览占位） */
export default function Home() {
  const [user, setUser] = useState<UserInfo | null>(null)

  useEffect(() => {
    fetchCurrentUser().then(setUser).catch(() => undefined)
  }, [])

  return (
    <Row gutter={[16, 16]}>
      <Col span={24}>
        <Card>
          <Typography.Title level={4} style={{ margin: 0 }}>
            你好，{user?.displayName ?? user?.username ?? '...'} 👋
          </Typography.Title>
          <Typography.Text type="secondary">
            OpenForge PLM M1 基础平台已就绪 —— 认证、RBAC 权限、组织树、编号规则引擎均已上线。
          </Typography.Text>
        </Card>
      </Col>
      <Col span={8}>
        <Card>
          <Statistic title="当前版本" value="M1" suffix={<Tag color="orange">dev</Tag>} />
        </Card>
      </Col>
      <Col span={8}>
        <Card>
          <Statistic title="我的角色" value={user?.roles?.join(' / ') ?? '—'} valueStyle={{ fontSize: 20 }} />
        </Card>
      </Col>
      <Col span={8}>
        <Card>
          <Statistic title="下一里程碑" value="M2 核心 PLM" valueStyle={{ fontSize: 20 }} />
        </Card>
      </Col>
    </Row>
  )
}
