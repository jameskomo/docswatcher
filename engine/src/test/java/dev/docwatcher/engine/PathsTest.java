package dev.docwatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PathsTest {

  @ParameterizedTest
  @CsvSource({
      "README.md, true",
      "docs/x.rst, true",
      "requirements.txt, true",
      "src/main.py, false",
      "test/a.js, true",
      "src/__tests__/a.js, true",
      "src/a.test.js, true",
      "src/a_test.go, true",
      "src/FooTest.java, true",
      "src/testing/a.js, false",
      "fixtures/x/y.py, true",
      "attest/a.js, false",
  })
  void classifies(String path, boolean docOrTest) {
    assertThat(Paths.isDocOrTest(path)).as(path).isEqualTo(docOrTest);
  }
}
