package dev.docswatcher.app.api;

import dev.docswatcher.app.auth.MemberAccess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The fix workflow template. Public content, so signed-in members may fetch it too. */
@RestController
@MemberAccess
public class SetupController {

  @GetMapping(value = "/api/setup/workflow", produces = "application/yaml")
  public String workflow() throws IOException {
    return new ClassPathResource("templates/docswatcher-fix.yml").getContentAsString(StandardCharsets.UTF_8);
  }

  @GetMapping(value = "/api/setup/workflow.txt", produces = MediaType.TEXT_PLAIN_VALUE)
  public String workflowText() throws IOException {
    return workflow();
  }
}
