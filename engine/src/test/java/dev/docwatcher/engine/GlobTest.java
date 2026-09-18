package dev.docwatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GlobTest {

  @ParameterizedTest
  @CsvSource({
      "**/*, a.py, true",
      "**/*, x/y/a.py, true",
      "**/*.java, A.java, true",
      "**/*.java, src/A.java, true",
      "**/*.java, src/A.py, false",
      "'**/*.{java,py}', src/A.py, true",
      "**/node_modules/**, node_modules/x/y.js, true",
      "**/node_modules/**, src/node_modules/y.js, true",
      "**/node_modules/**, src/nodemodules/y.js, false",
      "**/*.lock, yarn.lock, true",
      "*.py, a.py, true",
      "*.py, x/a.py, false",
  })
  void matches(String glob, String path, boolean expected) {
    assertThat(Glob.of(glob).matches(path)).as(glob + " vs " + path).isEqualTo(expected);
  }
}
