package com.openforge.connector.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/** 通用分页响应（与 material/metadata 服务同构：list,total,page,pageSize）。 */
@Data
@AllArgsConstructor
public class PageResponse<T> {

    private List<T> list;
    private long total;
    private long page;
    private long pageSize;

    public static <E, T> PageResponse<T> from(Page<E> p, Function<E, T> mapper) {
        return new PageResponse<>(p.getRecords().stream().map(mapper).toList(),
                p.getTotal(), p.getCurrent(), p.getSize());
    }
}
