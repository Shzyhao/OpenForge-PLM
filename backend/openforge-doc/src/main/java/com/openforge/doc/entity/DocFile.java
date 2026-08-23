package com.openforge.doc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("doc_file")
public class DocFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docInfoId;

    private String fileName;

    private String storageKey;

    private Long fileSize;

    private String sha256;

    private LocalDateTime createdAt;
}
