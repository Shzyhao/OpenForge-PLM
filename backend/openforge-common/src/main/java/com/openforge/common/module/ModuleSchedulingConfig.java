package com.openforge.common.module;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 模块注册心跳所需的调度开关（随 common 被 scanBasePackages 扫入各服务）。 */
@Configuration
@EnableScheduling
public class ModuleSchedulingConfig {
}
