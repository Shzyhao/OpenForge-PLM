package com.openforge.metadata.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/** 通用分页响应（与 material 服务同构）。 */
@Data
@AllArgsConstructor
public class PageResponse<T> {

    private long total;
    private long page;
    private long pageSize;
    private List<T> items;

    public static <E, T> PageResponse<T> from(Page<E> p, Function<E, T> mapper) {
        return new PageResponse<>(p.getTotal(), p.getCurrent(), p.getSize(),
                p.getRecords().stream().map(mapper).toList());
    }
}
