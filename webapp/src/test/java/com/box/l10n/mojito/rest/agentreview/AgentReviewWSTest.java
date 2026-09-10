package com.box.l10n.mojito.rest.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.Artifact;
import com.box.l10n.mojito.service.agentreview.AgentReviewEvidenceService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

public class AgentReviewWSTest {
  private final AgentReviewEvidenceService evidence = mock(AgentReviewEvidenceService.class);
  private final AgentReviewWS controller = new AgentReviewWS(null, null, evidence);

  @Test
  public void rasterEvidenceIsViewableWithoutExposingItsBase64Envelope() {
    byte[] bytes = new byte[] {(byte) 137, 80, 78, 71};
    when(evidence.read(1, 2, "digest"))
        .thenReturn(
            new Artifact(
                "digest", "image/png", Base64.getEncoder().encodeToString(bytes), bytes.length));
    var response = controller.projectArtifact(1, 2, "digest");
    assertThat(response.getBody()).containsExactly(bytes);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("inline");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");
  }

  @Test
  public void activeDocumentsAreDownloadsEvenWhenUploadedWithAnInlineMediaType() {
    byte[] bytes = "<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8);
    for (String contentType :
        new String[] {
          "image/svg+xml", "text/html", "application/xhtml+xml", "image/png; charset=UTF-8"
        }) {
      when(evidence.read(1, 2, "digest"))
          .thenReturn(
              new Artifact(
                  "digest", contentType, Base64.getEncoder().encodeToString(bytes), bytes.length));
      var response = controller.projectArtifact(1, 2, "digest");
      assertThat(response.getHeaders().getContentType())
          .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
      assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
          .startsWith("attachment;");
      assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }
  }
}
