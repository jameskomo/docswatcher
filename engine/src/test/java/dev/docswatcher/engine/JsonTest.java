package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

  @Test
  void matchesJsonStringifyWithTwoSpaces() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("a", List.of(1, "x"));
    m.put("b", Map.of());
    m.put("c", List.of());
    m.put("d", "q\"\n" + (char) 1);
    String out = Json.write(m);
    String expected = "{\n  \"a\": [\n    1,\n    \"x\"\n  ],\n  \"b\": {},\n  \"c\": [],\n  \"d\": \"q\\\"\\n\\" + "u0001\"\n}\n";
    assertThat(out).isEqualTo(expected);
  }

  @Test
  void omitsEmptyContextAndKeepsNullVersion() {
    Contract c = new Contract("p:model:m", "p", "model", "m", "medium",
        List.of(new Evidence("a.py", 1, 1, "m", "d", "literal")), null);
    assertThat(Json.write(c)).doesNotContain("context");
    Context ctx = new Context(new Context.Sdk("npm", "x", null), null);
    assertThat(Json.write(ctx)).isEqualTo("{\n  \"sdk\": {\n    \"ecosystem\": \"npm\",\n    \"package\": \"x\",\n    \"version\": null\n  }\n}\n");
  }
}
