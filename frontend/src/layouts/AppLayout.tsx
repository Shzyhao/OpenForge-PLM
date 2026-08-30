import { useEffect, useMemo, useState } from 'react'
import { Avatar, Button, Dropdown, Layout, Menu, Result, Spin, Tag, theme, Typography } from 'antd'
import {
  ApartmentOutlined, AppstoreOutlined, BellOutlined, BlockOutlined, BookOutlined, FileTextOutlined,
  HomeOutlined, KeyOutlined, LogoutOutlined, MoonOutlined, ProjectOutlined, SunOutlined, SwapOutlined,
  TableOutlined, TeamOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { clearToken, getPasswordStatus } from '../api/client'
import { fetchCurrentUser, type UserInfo } from '../api/user'
import { fetchEnabledModules } from '../api/modules'
import AiAssistant from '../components/AiAssistant'
import Logo from '../components/Logo'
import PasswordModal from '../components/PasswordModal'
import { PermContext, type PermContextValue } from '../perm/PermContext'
import { useThemeMode } from '../theme/ThemeMode'

const { Header, Sider, Content } = Layout

/** 菜单项与权限点映射（menu:xxx 为 V15 菜单权限编码） */
interface NavItem {
  key: string
  menu: string
  icon: React.ReactElement
  label: string
  module?: string
}

const NAV_ITEMS: NavItem[] = [
  { key: '/', menu: 'menu:dashboard', icon: <HomeOutlined />, label: '工作台' },
  { key: '/tasks', menu: 'menu:tasks', icon: <BellOutlined />, label: '我的待办' },
  { key: '/material', menu: 'menu:material', icon: <AppstoreOutlined />, label: '物料', module: 'material' },
  { key: '/bom', menu: 'menu:bom', icon: <ProjectOutlined />, label: 'BOM', module: 'material' },
  { key: '/doc', menu: 'menu:doc', icon: <FileTextOutlined />, label: '文档', module: 'doc' },
  { key: '/change', menu: 'menu:change', icon: <SwapOutlined />, label: '变更', module: 'change' },
  { key: '/workflow', menu: 'menu:workflow', icon: <ApartmentOutlined />, label: '流程', module: 'workflow' },
  { key: '/knowledge', menu: 'menu:knowledge', icon: <BookOutlined />, label: '知识库', module: 'knowledge' },
  { key: '/project', menu: 'menu:project', icon: <ProjectOutlined />, label: '项目', module: 'project' },
  { key: '/meta/objects', menu: 'menu:meta', icon: <BlockOutlined />, label: '对象建模', module: 'metadata' },
  { key: '/meta/data', menu: 'menu:meta', icon: <TableOutlined />, label: '动态数据', module: 'metadata' },
  { key: '/meta/designer', menu: 'menu:meta', icon: <BlockOutlined />, label: '界面设计', module: 'metadata' },
  { key: '/system/users', menu: 'menu:system', icon: <TeamOutlined />, label: '用户管理' },
  { key: '/system/roles', menu: 'menu:system', icon: <TeamOutlined />, label: '角色权限' },
  { key: '/system/logs', menu: 'menu:system', icon: <FileTextOutlined />, label: '安全日志' },
  { key: '/system/modules', menu: 'menu:system', icon: <BlockOutlined />, label: '模块管理' },
]

/** 侧边栏分组（顺序即展示顺序） */
const NAV_GROUP_ORDER = ['概览', '产品数据', '协作', '知识', '低代码', '系统'] as const
const NAV_GROUP_OF: Record<string, string> = {
  '/': '概览', '/tasks': '概览',
  '/material': '产品数据', '/bom': '产品数据', '/doc': '产品数据',
  '/change': '协作', '/workflow': '协作',
  '/knowledge': '知识', '/project': '知识',
  '/meta/objects': '低代码', '/meta/data': '低代码', '/meta/designer': '低代码',
  '/system/users': '系统', '/system/roles': '系统', '/system/logs': '系统', '/system/modules': '系统',
}

const ROLE_COLORS: Record<string, string> = { ADMINS: 'volcano', ENGINEER: 'geekblue', VIEWER: 'default' }

export default function AppLayout() {
  const [user, setUser] = useState<UserInfo | null>(null)
  const [loading, setLoading] = useState(true)
  // 启用模块清单（A4 设计 3.5 菜单注册表驱动）：null = 注册中心不可达 → 回退全显
  const [enabledModules, setEnabledModules] = useState<Set<string> | null>(null)
  const navigate = useNavigate()
  const location = useLocation()
  const { token: themeToken } = theme.useToken()
  const { mode, toggle } = useThemeMode()

  // 密码状态弹窗（方案 E3/E4）
  const savedStatus = getPasswordStatus()
  const [pwModal, setPwModal] = useState<'FORCE_CHANGE' | 'EXPIRING_SOON' | 'VOLUNTARY' | null>(
    savedStatus === 'FORCE_CHANGE' || savedStatus === 'EXPIRED' ? 'FORCE_CHANGE'
      : savedStatus === 'EXPIRING_SOON' ? 'EXPIRING_SOON' : null)

  useEffect(() => {
    fetchCurrentUser()
      .then(setUser)
      .catch(() => { clearToken(); navigate('/login') })
      .finally(() => setLoading(false))
  }, [navigate])

  // 模块菜单注册表驱动（A4-3）：停用模块的菜单入口同步隐藏
  useEffect(() => {
    fetchEnabledModules()
      .then((modules) => setEnabledModules(new Set(modules.map((m) => m.moduleKey))))
      .catch(() => setEnabledModules(null))   // 注册中心不可达：保守全显
  }, [])

  const permValue: PermContextValue = useMemo(() => ({
    user,
    hasPerm: (code) => user?.userType === 'SUPER' || (user?.permissions?.includes(code) ?? false),
    hasMenu: (menu) => user?.userType === 'SUPER' || (user?.menus?.includes(menu) ?? false),
  }), [user])

  const visibleItems = NAV_ITEMS.filter(item =>
    permValue.hasMenu(item.menu)
    && (item.module === undefined || enabledModules === null || enabledModules.has(item.module)))

  // 分组菜单（antd group 节点）
  const groupedMenuItems = NAV_GROUP_ORDER
    .map(group => ({
      type: 'group' as const,
      label: group,
      children: visibleItems
        .filter(item => (NAV_GROUP_OF[item.key] ?? '系统') === group)
        .map(({ key, icon, label }) => ({ key, icon, label })),
    }))
    .filter(g => g.children.length > 0)

  // 路由守卫（方案 F4）：当前路径对应菜单不可见 → 403
  const activeNav = NAV_ITEMS.find(item =>
    item.key !== '/' && (location.pathname === item.key || location.pathname.startsWith(item.key + '/')))
    ?? (location.pathname === '/' ? NAV_ITEMS[0] : undefined)
  const forbidden = activeNav !== undefined && !permValue.hasMenu(activeNav.menu)

  if (loading) {
    return <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh' }}><Spin /></div>
  }

  return (
    <PermContext.Provider value={permValue}>
      <Layout style={{ minHeight: '100vh' }}>
        <Sider theme={mode === 'dark' ? 'dark' : 'light'} width={210}
          style={{ borderRight: `1px solid ${themeToken.colorBorderSecondary}`, overflow: 'auto' }}>
          <div style={{ height: 56, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8 }}>
            <Logo size={26} />
            <span style={{ fontWeight: 700, fontSize: 16 }}>OpenForge</span>
          </div>
          <Menu mode="inline" selectedKeys={[activeNav?.key ?? '/']}
            items={groupedMenuItems as never}
            onClick={({ key }) => navigate(key)} />
        </Sider>
        <Layout>
          <Header style={{
            background: themeToken.colorBgContainer,
            borderBottom: `1px solid ${themeToken.colorBorderSecondary}`,
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            paddingInline: 24, gap: 16,
          }}>
            <Typography.Text strong style={{ fontSize: 15 }}>
              {activeNav?.label ?? '工作台'}
            </Typography.Text>
            <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Button
              type="text"
              aria-label="切换主题"
              icon={mode === 'dark' ? <SunOutlined /> : <MoonOutlined />}
              onClick={toggle}
            />
            <AiAssistant />
            {user && (
              <>
                {user.userType === 'SUPER' && <Tag color="red">admin</Tag>}
                {user.roles.map(role => (
                  <Tag key={role} color={ROLE_COLORS[role] ?? 'default'}>{role}</Tag>
                ))}
                <Dropdown menu={{
                  items: [
                    { key: 'password', icon: <KeyOutlined />, label: '修改密码',
                      onClick: () => setPwModal('VOLUNTARY') },
                    { type: 'divider' },
                    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true,
                      onClick: () => { clearToken(); navigate('/login') } },
                  ],
                }}>
                  <span style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8 }}>
                    <Avatar size="small" style={{ backgroundColor: themeToken.colorPrimary }}>
                      {(user.displayName || user.username).charAt(0)}
                    </Avatar>
                    {user.displayName || user.username}
                  </span>
                </Dropdown>
              </>
            )}
            </div>
          </Header>
          <Content style={{ padding: 24, background: themeToken.colorBgLayout }}>
            {forbidden
              ? <Result status="403" title="无权访问" subTitle="您没有该模块的访问权限，请联系管理员配置" />
              : <Outlet />}
          </Content>
        </Layout>
      </Layout>

      {pwModal && (
        <PasswordModal
          open mode={pwModal}
          onSuccess={() => {
            clearToken()
            window.location.href = '/login'
          }}
          onCancel={() => setPwModal(null)}
        />
      )}
    </PermContext.Provider>
  )
}
