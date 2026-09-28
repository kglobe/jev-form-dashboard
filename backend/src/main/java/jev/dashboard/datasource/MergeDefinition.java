package jev.dashboard.datasource;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 合併來源的定義（spec 4.2、4.3），存在 datasource.merge_json；JSON 用 op 分辨疊加和查表。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MergeDefinition.Union.class, name = "union"),
    @JsonSubTypes.Type(value = MergeDefinition.Lookup.class, name = "lookup")})
public sealed interface MergeDefinition {
    /**
     * inputs 的順序就是資料列的順序；sourceColumn 是標出每列來自哪個來源的欄位。
     * compare 是對照（B2 spec 4.1）：數字欄位依來源分開，JSON 沒寫就是 false。
     */
    record Union(List<UUID> inputs, List<Column> columns, String sourceColumn, boolean compare) implements MergeDefinition {
        public Union(List<UUID> inputs, List<Column> columns, String sourceColumn) {
            this(inputs, columns, sourceColumn, false);
        }

        /** from：輸入來源 id → 那個來源裡提供這個輸出欄位的欄位名稱。 */
        public record Column(String name, Map<UUID, String> from) {}
    }

    /** 主來源 left join 主檔：keys 全部相等才算對上，bring 是從主檔補上的欄位。 */
    record Lookup(UUID main, UUID lookup, List<Key> keys, List<Bring> bring) implements MergeDefinition {
        public record Key(String main, String lookup) {}

        public record Bring(String name, String from) {}
    }

    /** 直接用到的輸入來源，照定義裡的順序；定義要先驗證過。 */
    static List<UUID> inputIds(MergeDefinition d) {
        return switch (d) {
            case Union u -> u.inputs();
            case Lookup l -> List.of(l.main(), l.lookup());
        };
    }
}
