package com.openforge.metadata;

import com.openforge.metadata.entity.MetaObject;
import com.openforge.metadata.service.PublishedMeta;
import com.openforge.metadata.service.PublishedMetaCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 发布元数据 TTL 缓存：命中/驱逐/TTL 过期/关闭语义（纯单元，无 Spring 上下文）。 */
class PublishedMetaCacheTest {

    private PublishedMeta meta(String objectKey) {
        MetaObject object = new MetaObject();
        object.setObjectKey(objectKey);
        object.setStatus("PUBLISHED");
        return new PublishedMeta(object, List.of());
    }

    @Test
    @DisplayName("命中后返回同一快照，evict 后重新未命中")
    void putGetEvict() {
        PublishedMetaCache cache = new PublishedMetaCache(30);
        assertThat(cache.get("obj")).isNull();
        cache.put("obj", meta("obj"));
        assertThat(cache.get("obj")).isSameAs(cache.get("obj"));
        cache.evict("obj");
        assertThat(cache.get("obj")).isNull();
    }

    @Test
    @DisplayName("TTL 过期后未命中")
    void expiresAfterTtl() throws InterruptedException {
        PublishedMetaCache cache = new PublishedMetaCache(1);
        cache.put("obj", meta("obj"));
        assertThat(cache.get("obj")).isNotNull();
        Thread.sleep(1100);
        assertThat(cache.get("obj")).isNull();
    }

    @Test
    @DisplayName("ttl<=0 关闭缓存：put 后仍视为未命中")
    void disabledWhenTtlNonPositive() {
        PublishedMetaCache cache = new PublishedMetaCache(0);
        cache.put("obj", meta("obj"));
        assertThat(cache.get("obj")).isNull();
    }
}
