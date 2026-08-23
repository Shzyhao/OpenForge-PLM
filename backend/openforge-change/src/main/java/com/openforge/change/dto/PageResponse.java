package com.openforge.change.dto;

import java.util.List;

public record PageResponse<T>(List<T> list, long total, long page, long pageSize) {
}
