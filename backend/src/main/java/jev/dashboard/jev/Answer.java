package jev.dashboard.jev;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** score 是從 0 起算的小數（4 個選項回 0 到 3）；noul 只有 noul 題才有值。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Answer(String type, String choice, Double score, Double confidence, Double noul) {}
