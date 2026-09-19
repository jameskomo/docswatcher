package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ManifestLayerTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "requirements.txt | openai==1.51.0 | openai | 1.51.0 | 1",
      "requirements.txt | OpenAI>=1.0 | openai | 1.0 | 1",
      "requirements.txt | openai[voice] ; python_version>'3.10' | openai | | 1",
      "requirements.txt | not-openai==2 | openai | | 0",
      "requirements-dev.txt | pytest%openai | openai | | 1",
      "pyproject.toml | dependencies = [\"requests\", \"openai>=1.2.0\"] | openai | 1.2.0 | 1",
      "pyproject.toml | [tool.poetry.dependencies]%openai = \"^1.5\" | openai | 1.5 | 1",
      "go.mod | require github.com/stripe/stripe-go v82.1.0 | github.com/stripe/stripe-go | v82.1.0 | 1",
      "go.mod | require (%    github.com/stripe/stripe-go v82.1.0%) | github.com/stripe/stripe-go | v82.1.0 | 1",
      "Gemfile | gem \"shopify_api\", \"~> 14.0\" | shopify_api | ~> 14.0 | 1",
      "Gemfile | gem 'shopify_api' | shopify_api | | 1",
  })
  void textManifests(String file, String content, String pkg, String version, int hits) {
    SourceFile f = new SourceFile(file, content.replace("%", "\n"));
    String eco = ManifestLayer.ecosystemOf(file);
    ManifestLayer.Hit hit = switch (eco) {
      case "pypi" -> file.equals("pyproject.toml") ? ManifestLayer.pyproject(f, pkg) : ManifestLayer.requirements(f, pkg);
      case "go" -> ManifestLayer.gomod(f, pkg);
      case "rubygems" -> ManifestLayer.gemfile(f, pkg);
      default -> throw new IllegalStateException(eco);
    };
    if (hits == 0) {
      assertThat(hit).isNull();
    } else {
      assertThat(hit).isNotNull();
      assertThat(hit.version()).isEqualTo(version == null || version.isBlank() ? null : version);
    }
  }

  @Test
  void npmFindsDependencyAndDevDependencyButNotName() {
    SourceFile f = new SourceFile("package.json",
        "{\n  \"name\": \"stripe\",\n  \"dependencies\": { \"openai\": \"^4.0.0\" },\n  \"devDependencies\": { \"stripe\": \"^17.0.0\" }\n}\n");
    ManifestLayer.Hit s = ManifestLayer.npm(f, "stripe");
    assertThat(s).isNotNull();
    assertThat(s.version()).isEqualTo("^17.0.0");
    assertThat(f.lineAt(s.offset())).isEqualTo(4);
    assertThat(ManifestLayer.npm(f, "openai").version()).isEqualTo("^4.0.0");
    assertThat(ManifestLayer.npm(f, "left-pad")).isNull();
  }

  @Test
  void mavenFindsGroupAndArtifactWithVersion() {
    SourceFile f = new SourceFile("pom.xml", String.join("\n",
        "<project>",
        "  <dependencies>",
        "    <dependency>",
        "      <groupId>com.stripe</groupId>",
        "      <artifactId>stripe-java</artifactId>",
        "      <version>29.0.0</version>",
        "    </dependency>",
        "    <dependency>",
        "      <groupId>com.openai</groupId>",
        "      <artifactId>openai-java</artifactId>",
        "    </dependency>",
        "  </dependencies>",
        "</project>", ""));
    ManifestLayer.Hit s = ManifestLayer.maven(f, "com.stripe:stripe-java");
    assertThat(s.version()).isEqualTo("29.0.0");
    assertThat(f.lineAt(s.offset())).isEqualTo(5);
    assertThat(f.columnAt(s.offset())).isEqualTo(19);
    assertThat(ManifestLayer.maven(f, "com.openai:openai-java").version()).isNull();
    assertThat(ManifestLayer.maven(f, "com.stripe:openai-java")).isNull();
  }

  @Test
  void manifestEvidenceIsHighEvenInTxtFile() {
    Knowledge k = TestSupport.knowledge();
    List<Contract> contracts = TestSupport.scan(k, TestSupport.file("requirements.txt", "openai==1.0.0\n"));
    assertThat(contracts).singleElement().satisfies(c -> {
      assertThat(c.id()).isEqualTo("openai:sdk_package:openai");
      assertThat(c.confidence()).isEqualTo("high");
      assertThat(c.context().sdk().version()).isEqualTo("1.0.0");
    });
  }
}
