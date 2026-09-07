import { forwardRef, useEffect, useImperativeHandle, useMemo } from 'react'
import { Button, Form, Input, InputNumber, Select, Space, Switch, Typography, message } from 'antd'
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons'
import type { ConnType, Credential } from '../api/connector'

/**
 * 连接器配置面板（NodeConfigPanel，集成编排器 MVP 设计 §8 关键预留点）：
 * 全部配置表单收于此组件，P3 多步骤编排画布上每个步骤节点直接复用。
 * spec 契约见集成编排器 MVP 设计 §4.1（schemaVersion=1）。
 */

export interface ParamDef {
  name: string
  required: boolean
}

/** 父组件经 ref 调用 collect：表单校验失败返回 null（错误提示由表单自带）。 */
export interface ConnectorConfigPanelHandle {
  collect: () => Promise<Record<string, unknown> | null>
}

interface Props {
  connType: ConnType
  spec: Record<string, unknown>
  credentials: Credential[]
}

function paramsOf(spec: Record<string, unknown>): ParamDef[] {
  const schema = spec.parameterSchema as { properties?: Record<string, unknown>; required?: string[] } | undefined
  const props = schema?.properties ?? {}
  const required = new Set(schema?.required ?? [])
  const rows = Object.keys(props).map((name) => ({ name, required: required.has(name) }))
  return rows.length > 0 ? rows : [{ name: '', required: false }]
}

function parseJsonText(text: string, field: string): unknown {
  const trimmed = text.trim()
  if (!trimmed) return {}
  try {
    return JSON.parse(trimmed)
  } catch {
    message.error(`${field} 不是合法 JSON`)
    throw new Error(field)
  }
}

const ConnectorConfigPanel = forwardRef<ConnectorConfigPanelHandle, Props>(
  function ConnectorConfigPanel({ connType, spec, credentials }, ref) {
    const [form] = Form.useForm()

    const initialValues = useMemo(() => ({
      method: (spec.method as string) ?? 'POST',
      url: (spec.url as string) ?? '',
      headersText: JSON.stringify(spec.headers ?? {}, null, 2),
      bodyText: JSON.stringify((spec.requestTemplate as Record<string, unknown>)?.body ?? {}, null, 2),
      timeoutMs: (spec.timeoutMs as number) ?? 5000,
      maxAttempts: (spec.retry as { maxAttempts?: number })?.maxAttempts ?? 1,
      backoffMs: (spec.retry as { backoffMs?: number })?.backoffMs ?? 500,
      credentialRef: (spec.credentialRef as string) ?? undefined,
      jdbcUrl: (spec.datasource as { jdbcUrl?: string })?.jdbcUrl ?? '',
      username: (spec.datasource as { username?: string })?.username ?? '',
      passwordRef: (spec.passwordRef as string) ?? undefined,
      tablesText: ((spec.allowedTables as string[]) ?? []).join(','),
      sqlTemplate: (spec.sqlTemplate as string) ?? '',
      maxRows: (spec.maxRows as number) ?? 200,
      params: paramsOf(spec),
    }), [spec])

    useEffect(() => {
      form.setFieldsValue(initialValues)
    }, [form, initialValues])

    const buildParams = (rows: ParamDef[]) => {
      const valid = rows.filter((r) => r.name.trim())
      const properties: Record<string, unknown> = {}
      valid.forEach((r) => { properties[r.name.trim()] = { type: 'string' } })
      const required = valid.filter((r) => r.required).map((r) => r.name.trim())
      return required.length > 0
        ? { type: 'object', properties, required }
        : { type: 'object', properties }
    }

    const collect = async (): Promise<Record<string, unknown> | null> => {
      let values
      try {
        values = await form.validateFields()
      } catch {
        return null
      }
      const params = buildParams(values.params as ParamDef[])
      try {
        if (connType === 'HTTP_REST') {
          const headers = parseJsonText(values.headersText, '请求头') as Record<string, unknown>
          const body = parseJsonText(values.bodyText, '请求体模板') as Record<string, unknown>
          return {
            schemaVersion: 1,
            method: values.method,
            url: values.url.trim(),
            headers,
            timeoutMs: values.timeoutMs,
            ...(values.credentialRef ? { credentialRef: values.credentialRef } : {}),
            parameterSchema: params,
            ...(Object.keys(body).length > 0 ? { requestTemplate: { body } } : {}),
            retry: { maxAttempts: values.maxAttempts, backoffMs: values.backoffMs },
          } as unknown as Record<string, unknown>
        }
      } catch {
        return null
      }
      return {
        schemaVersion: 1,
        datasource: { jdbcUrl: values.jdbcUrl.trim(), username: values.username.trim() },
        ...(values.passwordRef ? { passwordRef: values.passwordRef } : {}),
        allowedTables: String(values.tablesText).split(',').map((t) => t.trim()).filter(Boolean),
        parameterSchema: params,
        sqlTemplate: values.sqlTemplate,
        maxRows: values.maxRows,
        timeoutMs: values.timeoutMs,
      } as unknown as Record<string, unknown>
    }

    useImperativeHandle(ref, () => ({ collect }), [connType])

    const httpCreds = credentials.filter((c) => c.authType !== 'JDBC_PASSWORD')
    const jdbcCreds = credentials.filter((c) => c.authType === 'JDBC_PASSWORD')

    return (
      <Form form={form} layout="vertical">
        <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
          参数定义（占位符 {connType === 'HTTP_REST' ? '{{name}}' : ':name'} 引用；required 为调用方必填）
        </Typography.Text>
        <Form.List name="params">
          {(fields, { add, remove }) => (
            <>
              {fields.map((field) => (
                <Space key={field.key} align="baseline" style={{ display: 'flex', marginBottom: 4 }}>
                  <Form.Item name={[field.name, 'name']} noStyle>
                    <Input placeholder="参数名，如 materialNumber" style={{ width: 240 }} />
                  </Form.Item>
                  <Form.Item name={[field.name, 'required']} valuePropName="checked" noStyle>
                    <Switch size="small" checkedChildren="必填" unCheckedChildren="可选" />
                  </Form.Item>
                  <Button type="text" size="small" icon={<DeleteOutlined />} onClick={() => remove(field.name)} />
                </Space>
              ))}
              <Button block type="dashed" icon={<PlusOutlined />}
                onClick={() => add({ name: '', required: false })}>添加参数</Button>
            </>
          )}
        </Form.List>

        {connType === 'HTTP_REST' ? (
          <>
            <Typography.Text type="secondary" style={{ display: 'block', margin: '16px 0 12px' }}>
              连接配置（认证经凭据引用注入 Authorization / 自定义头）
            </Typography.Text>
            <Space size="large" style={{ display: 'flex', marginBottom: 0 }} wrap>
              <Form.Item name="method" label="Method" style={{ marginBottom: 0 }}>
                <Select style={{ width: 110 }}
                  options={['GET', 'POST', 'PUT', 'PATCH'].map((m) => ({ value: m, label: m }))} />
              </Form.Item>
              <Form.Item name="url" label="URL" rules={[{ required: true, message: '必填' }]}
                style={{ minWidth: 420, marginBottom: 0 }}>
                <Input placeholder="https://erp.example.com/api/inventory" />
              </Form.Item>
            </Space>
            <Space size="large" style={{ display: 'flex', marginTop: 12 }} wrap>
              <Form.Item name="credentialRef" label="认证凭据（可选）" style={{ minWidth: 220, marginBottom: 0 }}>
                <Select allowClear placeholder="无认证"
                  options={httpCreds.map((c) => ({ value: c.credCode, label: `${c.credName}（${c.credCode}）` }))} />
              </Form.Item>
              <Form.Item name="timeoutMs" label="超时(ms)" style={{ marginBottom: 0 }}>
                <InputNumber min={100} max={30000} style={{ width: 120 }} />
              </Form.Item>
              <Form.Item name="maxAttempts" label="重试次数" style={{ marginBottom: 0 }}>
                <InputNumber min={1} max={5} style={{ width: 90 }} />
              </Form.Item>
              <Form.Item name="backoffMs" label="重试退避(ms)" style={{ marginBottom: 0 }}>
                <InputNumber min={0} max={10000} style={{ width: 110 }} />
              </Form.Item>
            </Space>
            <Form.Item name="headersText" label="请求头（JSON 对象）" style={{ marginTop: 12 }}>
              <Input.TextArea rows={3} placeholder='{"Accept": "application/json"}'
                style={{ fontFamily: 'monospace' }} />
            </Form.Item>
            <Form.Item name="bodyText" label="请求体模板（JSON；值可用 {{参数名}} 占位）">
              <Input.TextArea rows={5} placeholder={'{"materialNumber": "{{materialNumber}}"}'}
                style={{ fontFamily: 'monospace' }} />
            </Form.Item>
          </>
        ) : (
          <>
            <Typography.Text type="secondary" style={{ display: 'block', margin: '16px 0 12px' }}>
              数据源（只读账号；仅 PostgreSQL / MySQL）
            </Typography.Text>
            <Form.Item name="jdbcUrl" label="JDBC URL" rules={[{ required: true, message: '必填' }]}>
              <Input placeholder="jdbc:postgresql://legacy-mes:5432/mes" style={{ fontFamily: 'monospace' }} />
            </Form.Item>
            <Space size="large" style={{ display: 'flex' }} wrap>
              <Form.Item name="username" label="只读用户名" rules={[{ required: true, message: '必填' }]}
                style={{ marginBottom: 0 }}>
                <Input placeholder="readonly_user" />
              </Form.Item>
              <Form.Item name="passwordRef" label="密码凭据" rules={[{ required: true, message: '必填' }]}
                style={{ minWidth: 220, marginBottom: 0 }}>
                <Select placeholder="选择 JDBC_PASSWORD 凭据"
                  options={jdbcCreds.map((c) => ({ value: c.credCode, label: `${c.credName}（${c.credCode}）` }))} />
              </Form.Item>
            </Space>
            <Form.Item name="tablesText" label="表白名单（逗号分隔；SQL 引用表必须全部命中）"
              style={{ marginTop: 12 }} rules={[{ required: true, message: '必填' }]}>
              <Input placeholder="mes_stock, mes_work_order" style={{ fontFamily: 'monospace' }} />
            </Form.Item>
            <Form.Item name="sqlTemplate" label="SQL 模板（仅 SELECT/WITH；命名参数 :参数名 绑定）"
              rules={[{ required: true, message: '必填' }]}>
              <Input.TextArea rows={4}
                placeholder={'SELECT item_code, qty FROM mes_stock WHERE item_code = :materialNumber'}
                style={{ fontFamily: 'monospace' }} />
            </Form.Item>
            <Space size="large" style={{ display: 'flex' }} wrap>
              <Form.Item name="maxRows" label="行数上限" style={{ marginBottom: 0 }}>
                <InputNumber min={1} max={1000} style={{ width: 110 }} />
              </Form.Item>
              <Form.Item name="timeoutMs" label="超时(ms)" style={{ marginBottom: 0 }}>
                <InputNumber min={100} max={30000} style={{ width: 120 }} />
              </Form.Item>
            </Space>
          </>
        )}
      </Form>
    )
  },
)

export default ConnectorConfigPanel
