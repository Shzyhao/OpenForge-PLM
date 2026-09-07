package com.openforge.connector.dto;

import lombok.Data;

import java.util.Map;

/** 试运行/调用请求：参数键值对（须满足 spec.parameterSchema）。 */
@Data
public class InvokeRequest {

    /** 调用参数；无参连接器传空对象或不传 */
    private Map<String, Object> params;
}
