package com.openforge.doc.dto;

import java.util.List;

public record PageResponse<T>(List<T> list, long total, long page, long pageSize) {
}
