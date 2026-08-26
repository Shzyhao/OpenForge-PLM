-- A4-2：网关动态路由所需的服務地址列（注册时由服务自身上报，可用 openforge.module.service-uri 覆盖）
ALTER TABLE sys_module ADD COLUMN service_uri VARCHAR(255);
