package jev.dashboard;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import jev.dashboard.jev.Answer;

public final class Fixtures {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 對應 sales.csv 的 schema；memo 標為敏感。 */
    public static final List<FieldSchema> SALES_SCHEMA = List.of(
        new FieldSchema("order_date", FieldType.DATE, 12, List.of("2024-01-05"), false, "2024-01-05", "2024-06-20"),
        new FieldSchema("amount", FieldType.NUMBER, 12, List.of("1200"), false, null, null),
        new FieldSchema("region", FieldType.STRING, 4, List.of("北區"), false, null, null),
        new FieldSchema("product", FieldType.STRING, 3, List.of("A"), false, null, null),
        new FieldSchema("qty", FieldType.NUMBER, 8, List.of("3"), false, null, null),
        new FieldSchema("memo", FieldType.STRING, 12, List.of("機密備註"), true, null, null));

    private Fixtures() {}

    public static String json(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/jev/" + name)) {
            return new String(in.readAllBytes(), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Map<String, Answer> answers(String name) {
        try {
            return JSON.convertValue(JSON.readTree(json(name)).get("answers"), new TypeReference<Map<String, Answer>>() {});
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
