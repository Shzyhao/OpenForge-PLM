package com.openforge.material.dto;

import java.util.List;

/** 通用分页响应。 */
public record PageResponse<T>(List<T> list, long total, long page, long pageSize) {
}
