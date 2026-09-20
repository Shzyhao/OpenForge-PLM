package com.openforge.material.service;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.material.entity.Part;
import com.openforge.material.mapper.PartRecycleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 物料回收站（v1.23 设计 §3）：软删行列表 + 恢复。
 * 恢复护栏：目标行必须处于软删态；编码被在册记录占用时明确报错，不静默改号。
 * 权限与删除同权（part:delete），见 PartController。
 */
@Service
@RequiredArgsConstructor
public class RecycleService {

    private final PartRecycleMapper recycleMapper;

    public List<Part> trashedParts() {
        return recycleMapper.trashedParts();
    }

    @Transactional
    public Part restore(Long id) {
        Part part = recycleMapper.trashedPart(id);
        if (part == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "回收站中不存在该物料");
        }
        if (recycleMapper.liveCountByNumber(part.getPartNumber()) > 0) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                    "恢复失败：编码 " + part.getPartNumber() + " 已被其他在册物料占用");
        }
        if (recycleMapper.restorePart(id) != 1) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "回收站中不存在该物料");
        }
        return part;
    }
}
