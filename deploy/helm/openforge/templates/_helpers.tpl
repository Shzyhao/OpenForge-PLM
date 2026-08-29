{{/* 服务通用标签 */}}
{{- define "openforge.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: openforge
app.kubernetes.io/version: {{ $.Chart.AppVersion | quote }}
{{- end -}}

{{/* 后端 Deployment 通用环境变量（数据库/网关地址/信任链/模块注册地址） */}}
{{- define "openforge.javaEnv" -}}
- name: PG_HOST
  value: {{ $.Values.global.postgres.host | quote }}
- name: PG_PORT
  value: {{ $.Values.global.postgres.port | quote }}
- name: PG_DB
  value: {{ $.Values.global.postgres.database | quote }}
- name: PG_USER
  value: {{ $.Values.global.postgres.user | quote }}
- name: PG_PASSWORD
  value: {{ $.Values.global.postgres.password | quote }}
- name: AUTH_SERVICE_URI
  value: http://auth:8081
- name: INTERNAL_TOKEN
  value: {{ $.Values.global.internalToken | quote }}
- name: JWT_SECRET
  value: {{ $.Values.global.jwtSecret | quote }}
- name: NACOS_ENABLED
  value: {{ $.Values.global.nacos.enabled | quote }}
- name: NACOS_ADDR
  value: {{ $.Values.global.nacos.addr | quote }}
- name: MODULE_SERVICE_URI
  value: {{ printf "http://%s:%v" .name .port | quote }}
{{- end -}}
