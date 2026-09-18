package dev.docwatcher.engine;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Comparator;

/** One place a contract was observed. Line and column are 1-based. */
@JsonPropertyOrder({"path", "line", "column", "snippet", "detector", "layer"})
public record Evidence(String path, int line, int column, String snippet, String detector, String layer) {

  public static final Comparator<Evidence> ORDER =
      Comparator.comparing(Evidence::path)
          .thenComparingInt(Evidence::line)
          .thenComparingInt(Evidence::column)
          .thenComparing(Evidence::detector);
}
