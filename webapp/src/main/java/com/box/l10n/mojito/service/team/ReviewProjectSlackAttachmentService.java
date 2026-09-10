package com.box.l10n.mojito.service.team;

import com.box.l10n.mojito.service.review.ReviewProjectRequestScreenshotRepository;
import com.box.l10n.mojito.utils.ServerConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

@Service
public class ReviewProjectSlackAttachmentService {

  private static final int MAX_ATTACHMENT_LINKS = 5;

  private final ReviewProjectRequestScreenshotRepository screenshotRepository;
  private final ServerConfig serverConfig;

  public ReviewProjectSlackAttachmentService(
      ReviewProjectRequestScreenshotRepository screenshotRepository, ServerConfig serverConfig) {
    this.screenshotRepository = screenshotRepository;
    this.serverConfig = serverConfig;
  }

  public String buildAttachmentSummary(Long requestId) {
    String configuredUrl = serverConfig.getUrl();
    if (requestId == null || configuredUrl == null || configuredUrl.isBlank()) {
      return "";
    }
    String baseUrl = configuredUrl.trim().replaceAll("/+$", "");
    if (baseUrl.isEmpty()) {
      return "";
    }

    LinkedHashSet<String> imageNames = new LinkedHashSet<>();
    for (String imageName :
        screenshotRepository.findImageNamesByReviewProjectRequestId(requestId)) {
      if (imageName != null && !imageName.isBlank()) {
        imageNames.add(imageName.trim());
      }
    }
    if (imageNames.isEmpty()) {
      return "";
    }

    List<String> links = new ArrayList<>();
    String requestUrl = baseUrl + "/review-projects?requestId=" + requestId;
    for (String imageName : imageNames) {
      if (links.size() == MAX_ATTACHMENT_LINKS) {
        break;
      }
      // The request UI also accepts external and browser-local references, which aren't image keys.
      boolean externalReference =
          imageName.matches("(?is)^https?://.*")
              || imageName.startsWith("//")
              || imageName.startsWith("data:")
              || imageName.startsWith("blob:");
      String url =
          externalReference
              ? requestUrl
              : baseUrl + "/api/images/" + UriUtils.encode(imageName, StandardCharsets.UTF_8);
      String label =
          imageName.startsWith("data:") || imageName.startsWith("blob:")
              ? "Attachment " + (links.size() + 1)
              : imageName;
      label =
          label
              .replaceAll("\\s+", " ")
              .replace("|", " · ")
              .replace("&", "&amp;")
              .replace("<", "&lt;")
              .replace(">", "&gt;");
      links.add("<" + url + "|" + label + ">");
    }
    if (imageNames.size() > MAX_ATTACHMENT_LINKS) {
      links.add("<" + requestUrl + "|View all " + imageNames.size() + " attachments>");
    }
    return "\nScreenshots / attachments: " + String.join(", ", links);
  }
}
