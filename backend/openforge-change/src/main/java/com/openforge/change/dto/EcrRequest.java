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
}
