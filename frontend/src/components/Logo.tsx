import { ToolOutlined } from '@ant-design/icons'

/** 品牌标识（锻炉橙圆角块 + 锻造工具图形，替代 emoji） */
export default function Logo({ size = 28 }: { size?: number }) {
  return (
    <div style={{
      width: size, height: size, borderRadius: size * 0.28, flexShrink: 0,
      background: 'linear-gradient(135deg, #F25C05 0%, #D94E04 100%)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      boxShadow: '0 2px 6px rgba(242, 92, 5, 0.35)',
    }}>
      <ToolOutlined style={{ color: '#fff', fontSize: size * 0.52 }} />
    </div>
  )
}
