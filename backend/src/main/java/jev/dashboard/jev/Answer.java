package jev.dashboard.jev;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * score 是從 0 起算的小數（4 個選項回 0 到 3）；noul 只有 noul 題才有值。
 * probabilities、legend 原樣留著（Choice／Score 才有），除錯時看完整分布；用 JsonNode，格式有變也不會讓整個回應解析失敗。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Answer(String type, String choice, Double score, Double confidence, Double noul,
                     JsonNode probabilities, JsonNode legend) {
    public Answer(String type, String choice, Double score, Double confidence, Double noul) {
        this(type, choice, score, confidence, noul, null, null);
    }
}
