package com.openforge.change.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EcrRequest {

    @NotBlank
    private String title;

    private String reason;

    /** LOW/NORMAL/HIGH */
    private String urgency;

    /** 受影响对象 JSON（M4 结构化为 affected_items 表） */
    private String affectedItems;

    /** GENERIC/SUBSTITUTE_CHANGE/PART_STATE_CHANGE，缺省 GENERIC */
    private String changeType;

    /** 类型化明细 JSON：SUBSTITUTE_CHANGE={bomId,lineId,after:[...]}；PART_STATE_CHANGE={partId,targetState} */
    private String payload;
}
