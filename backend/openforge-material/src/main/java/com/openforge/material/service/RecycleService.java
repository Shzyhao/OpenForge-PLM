package com.openforge.material.service;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.material.entity.Bom;
import com.openforge.material.entity.Part;
import com.openforge.material.mapper.BomRecycleMapper;
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
    private final BomRecycleMapper bomRecycleMapper;

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

    // ===== BOM 回收站（v1.24）=====

    public List<Bom> trashedBoms() {
        return bomRecycleMapper.trashedBoms();
    }

    @Transactional
    public Bom restoreBom(Long id) {
        Bom bom = bomRecycleMapper.trashedBom(id);
        if (bom == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "回收站中不存在该BOM");
        }
        if (bomRecycleMapper.liveCountByNumber(bom.getBomNumber()) > 0) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                    "恢复失败：编号 " + bom.getBomNumber() + " 已被其他在册BOM占用");
        }
        if (bomRecycleMapper.restoreBom(id) != 1) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "回收站中不存在该BOM");
        }
        return bom;
    }

    // ===== 彻底删除（v1.24 设计 §2）：回收站内行 + 关联数据 GC，不可逆 =====

    /** 彻底删除物料：版本快照 GC；被 BOM 行/替代组引用（含软删）时明确拒绝。 */
    @Transactional
    public Part purgePart(Long id) {
        Part part = recycleMapper.trashedPart(id);
        if (part == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "回收站中不存在该物料");
        }
        int refs = bomRecycleMapper.bomLineRefCount(id) + bomRecycleMapper.substituteRefCount(id);
        if (refs > 0) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                    "物料 " + part.getPartNumber() + " 被 " + refs + " 处 BOM 行/替代组引用，"
                            + "请先彻底删除对应 BOM（含回收站中的）再彻底删除物料");
        }
        recycleMapper.deleteVersionsByPart(id);
        if (recycleMapper.purgePart(id) != 1) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "回收站中不存在该物料");
        }
        return part;
    }

    /** 彻底删除 BOM：行 + 替代组 GC（纯关系数据，无文件面）。 */
    @Transactional
    public Bom purgeBom(Long id) {
        Bom bom = bomRecycleMapper.trashedBom(id);
        if (bom == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "回收站中不存在该BOM");
        }
        bomRecycleMapper.deleteSubstitutesByBom(id);
        bomRecycleMapper.deleteLinesByBom(id);
        if (bomRecycleMapper.purgeBom(id) != 1) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "回收站中不存在该BOM");
        }
        return bom;
    }
}
