package com.iflytek.skillhub.dto;

import java.util.List;

/**
 * One home ranking in rank order.
 *
 * <p>{@code type} echoes the resolved ranking so a caller can tell "recent-popular" from a
 * fallback. An empty list is a real empty state.
 */
public record HomeRankingResponse(
        String type,
        List<HomeRankingItem> items
) {
}
