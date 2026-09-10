package com.box.l10n.mojito.service.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.review.ReviewProjectRequestScreenshotRepository;
import com.box.l10n.mojito.utils.ServerConfig;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class ReviewProjectSlackAttachmentServiceTest {

  private final ReviewProjectRequestScreenshotRepository screenshotRepository =
      Mockito.mock(ReviewProjectRequestScreenshotRepository.class);
  private final ServerConfig serverConfig = new ServerConfig();
  private final ReviewProjectSlackAttachmentService service =
      new ReviewProjectSlackAttachmentService(screenshotRepository, serverConfig);

  @Before
  public void setUp() {
    serverConfig.setUrl("https://mojito.example/app/");
  }

  @Test
  public void linksAuthenticatedAttachmentsWithEncodedKeysAndFullNames() {
    when(screenshotRepository.findImageNamesByReviewProjectRequestId(44L))
        .thenReturn(List.of("folder/checkout + détails & <draft>.png", "walkthrough.pdf"));

    assertThat(service.buildAttachmentSummary(44L))
        .isEqualTo(
            "\nScreenshots / attachments:"
                + " <https://mojito.example/app/api/images/"
                + "folder%2Fcheckout%20%2B%20d%C3%A9tails%20%26%20%3Cdraft%3E.png"
                + "|folder/checkout + détails &amp; &lt;draft&gt;.png>,"
                + " <https://mojito.example/app/api/images/walkthrough.pdf|walkthrough.pdf>");
  }

  @Test
  public void omitsBlankEntriesAndDuplicates() {
    when(screenshotRepository.findImageNamesByReviewProjectRequestId(44L))
        .thenReturn(Arrays.asList(null, " ", " screenshot.png ", "screenshot.png"));

    assertThat(service.buildAttachmentSummary(44L))
        .isEqualTo(
            "\nScreenshots / attachments:"
                + " <https://mojito.example/app/api/images/screenshot.png|screenshot.png>");
  }

  @Test
  public void keepsExternalAndBrowserLocalReferencesInTheRequestUi() {
    when(screenshotRepository.findImageNamesByReviewProjectRequestId(44L))
        .thenReturn(
            List.of(
                "https://example.com/screenshot.png",
                "//example.com/video.mp4",
                "data:image/png;base64,example",
                "blob:https://mojito.example/1234"));

    assertThat(service.buildAttachmentSummary(44L))
        .contains(
            "<https://mojito.example/app/review-projects?requestId=44|https://example.com/screenshot.png>",
            "<https://mojito.example/app/review-projects?requestId=44|//example.com/video.mp4>",
            "<https://mojito.example/app/review-projects?requestId=44|Attachment 3>",
            "<https://mojito.example/app/review-projects?requestId=44|Attachment 4>")
        .doesNotContain("/api/images/", "data:image", "blob:");
  }

  @Test
  public void normalizesLinkDelimitersAndNewlinesInLabels() {
    when(screenshotRepository.findImageNamesByReviewProjectRequestId(44L))
        .thenReturn(List.of("checkout|draft\n<@USER>.png"));

    assertThat(service.buildAttachmentSummary(44L))
        .isEqualTo(
            "\nScreenshots / attachments:"
                + " <https://mojito.example/app/api/images/checkout%7Cdraft%0A%3C%40USER%3E.png"
                + "|checkout · draft &lt;@USER&gt;.png>");
  }

  @Test
  public void capsLinksAndIncludesAllAttachmentsLink() {
    when(screenshotRepository.findImageNamesByReviewProjectRequestId(44L))
        .thenReturn(List.of("one.png", "two.png", "three.png", "four.png", "five.png", "six.png"));

    assertThat(service.buildAttachmentSummary(44L))
        .contains("/api/images/one.png", "/api/images/five.png")
        .doesNotContain("/api/images/six.png")
        .endsWith(
            "<https://mojito.example/app/review-projects?requestId=44|View all 6 attachments>");
  }

  @Test
  public void omitsSummaryWithoutAttachments() {
    when(screenshotRepository.findImageNamesByReviewProjectRequestId(44L)).thenReturn(List.of());

    assertThat(service.buildAttachmentSummary(44L)).isEmpty();
  }

  @Test
  public void omitsSummaryWithoutRequestOrServerUrl() {
    assertThat(service.buildAttachmentSummary(null)).isEmpty();
    serverConfig.setUrl(" ");
    assertThat(service.buildAttachmentSummary(44L)).isEmpty();

    verifyNoInteractions(screenshotRepository);
  }
}
