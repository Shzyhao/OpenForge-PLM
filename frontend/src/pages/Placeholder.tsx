import { Empty, Result } from 'antd'

/** 模块建设中占位页（M2 起逐个替换） */
export default function Placeholder({ title }: { title: string }) {
  return (
    <Result
      icon={<Empty />}
      title={`${title}`}
      subTitle="该模块按里程碑建设中 —— 参见仓库 Roadmap（README）"
    />
  )
}
