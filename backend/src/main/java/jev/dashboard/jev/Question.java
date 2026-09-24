package jev.dashboard.jev;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Question(String type, String instructions, Object criteria) {
    public static Question choice(String instructions, Map<String, String> criteria) {
        return new Question("choice", instructions, criteria);
    }

    public static Question score(String instructions, List<String> levels) {
        return new Question("score", instructions, levels);
    }

    public static Question noul(String instructions) {
        return new Question("noul", instructions, null);
    }
}
