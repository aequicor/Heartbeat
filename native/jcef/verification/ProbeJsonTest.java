import java.util.List;
import java.util.Map;

/** Codec contract checks; runs without JCEF/native libraries. */
public final class ProbeJsonTest {
    public static void main(String[] args) {
        Map<?, ?> parsed = (Map<?, ?>) ProbeJson.parse("{\"result\":{\"value\":{\"local\":null,\"dpr\":1.5,\"cookies\":{}}}}");
        Map<?, ?> value = (Map<?, ?>) ((Map<?, ?>) parsed.get("result")).get("value");
        if (value.get("local") != null || !value.get("dpr").equals(1.5)) throw new AssertionError("CDP parse");
        String text = "Куки \\\n\t\" 🍪";
        Object roundTrip = ProbeJson.parse(ProbeJson.encode(Map.of("text", text, "values", List.of(true, false))));
        if (!((Map<?, ?>) roundTrip).get("text").equals(text)) throw new AssertionError("JSON string round trip");
        try {
            ProbeJson.parse("{\"result\":1,\"result\":2}");
            throw new AssertionError("Duplicate keys accepted");
        } catch (IllegalArgumentException expected) {
            // A duplicated CDP key must not silently change which result gets asserted.
        }
        System.out.println("PASS ProbeJson contracts");
    }
}
