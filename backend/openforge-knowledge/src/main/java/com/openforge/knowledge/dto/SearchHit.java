package com.openforge.knowledge.dto;

public record SearchHit(Long itemId, String title, String summary, double score) {
}
