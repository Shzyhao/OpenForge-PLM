package com.openforge.connector.spi;

import java.util.Map;

/**
 * 连接器 SPI（集成编排器 MVP 设计 §5）：按 connType 注册一个实现，
 * 运行时（ConnectorRuntime）统一入口分发。实现方专注传输细节；
 * 出站白名单/参数校验/执行日志由运行时承担。
 */
public interface ConnectorSpi {

    /** 适配的连接器类型（conn_definition.conn_type 列值，如 HTTP_REST）。 */
    String type();

    /** 执行一次调用（spec 已解析校验、参数已校验齐备、凭据已解密）。 */
    ConnectorResult execute(ConnectorExecution execution);
}
