import { useEffect, useState } from 'react'
import { Avatar, Dropdown, Layout, Menu, Spin, Tag, theme } from 'antd'
import {
  ApartmentOutlined,
  AppstoreOutlined,
  BellOutlined,
  BookOutlined,
  FileTextOutlined,
  HomeOutlined,
  LogoutOutlined,
  ProjectOutlined,
  SwapOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { clearToken } from '../api/client'
import { fetchCurrentUser, type UserInfo } from '../api/user'
import AiAssistant from '../components/AiAssistant'

const { Header, Sider, Content } = Layout

/** 侧边导航菜单（全部模块已接入） */
const NAV_ITEMS = [
  { key: '/', icon: <HomeOutlined />, label: '工作台' },
  { key: '/tasks', icon: <BellOutlined />, label: '我的待办' },
  { key: '/material', icon: <AppstoreOutlined />, label: '物料' },
  { key: '/bom', icon: <ProjectOutlined />, label: 'BOM' },
  { key: '/doc', icon: <FileTextOutlined />, label: '文档' },
  { key: '/change', icon: <SwapOutlined />, label: '变更' },
  { key: '/workflow', icon: <ApartmentOutlined />, label: '流程' },
  { key: '/knowledge', icon: <BookOutlined />, label: '知识库' },
  { key: '/project', icon: <ProjectOutlined />, label: '项目' },
]

const ROLE_COLORS: Record<string, string> = {
  ADMIN: 'volcano',
  ENGINEER: 'geekblue',
  VIEWER: 'default',
}

export default function AppLayout() {
  const [user, setUser] = useState<UserInfo | null>(null)
  const [loading, setLoading] = useState(true)
  const navigate = useNavigate()
  const location = useLocation()
  const { token: themeToken } = theme.useToken()

  useEffect(() => {
    fetchCurrentUser()
      .then(setUser)
      .catch(() => {
        clearToken()
        navigate('/login')
      })
      .finally(() => setLoading(false))
  }, [navigate])

  const selectedKey =
    NAV_ITEMS.find((item) => item.key !== '/' && location.pathname.startsWith(item.key))?.key ?? '/'

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="light" width={200} style={{ borderRight: `1px solid ${themeToken.colorBorderSecondary}` }}>
        <div style={{ height: 56, display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 700, fontSize: 16 }}>
          🔨 OpenForge
        </div>
        <Menu
          mode="inline"
          selectedKeys={[selectedKey]}
          items={NAV_ITEMS}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: themeToken.colorBgContainer,
            borderBottom: `1px solid ${themeToken.colorBorderSecondary}`,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            paddingInline: 24,
            gap: 16,
          }}
        >
          <AiAssistant />
          <BellOutlined style={{ fontSize: 16, cursor: 'pointer' }} title="通知（M2）" />
          {loading ? (
            <Spin size="small" />
          ) : user ? (
            <>
              {user.roles.map((role) => (
                <Tag key={role} color={ROLE_COLORS[role] ?? 'default'}>
                  {role}
                </Tag>
              ))}
              <Dropdown
                menu={{
                  items: [
                    { key: 'profile', icon: <UserOutlined />, label: '个人中心（建设中）' },
                    { type: 'divider' },
                    {
                      key: 'logout',
                      icon: <LogoutOutlined />,
                      label: '退出登录',
                      danger: true,
                      onClick: () => {
                        clearToken()
                        navigate('/login')
                      },
                    },
                  ],
                }}
              >
                <span style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Avatar size="small" style={{ backgroundColor: themeToken.colorPrimary }}>
                    {(user.displayName || user.username).charAt(0)}
                  </Avatar>
                  {user.displayName || user.username}
                </span>
              </Dropdown>
            </>
          ) : null}
        </Header>
        <Content style={{ padding: 24, background: themeToken.colorBgLayout }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
