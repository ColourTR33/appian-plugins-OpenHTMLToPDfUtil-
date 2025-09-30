import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.appiancorp.suiteapi.common.exceptions.AppianException;
import com.appiancorp.suiteapi.content.ContentConstants;
import com.appiancorp.suiteapi.content.ContentService;

/**
 * Resolves image paths within an HTML document, converting Appian document IDs
 * into local file paths suitable for PDF generation with timeouts and placeholders.
 */
public class HTMLImageResolver {

  private static final Logger LOG = LogManager.getLogger(HTMLImageResolver.class);
  private static final String APPIAN_DOC_ID_ATTR = "data-docid";
  private static final String LEGACY_APPIAN_DOC_ID_ATTR = "appianDocId";

  private final ContentService contentService;
  private final long timeoutMs;
  private final String placeholderImageUri;

  /**
   * Constructs the resolver.
   *
   * @param contentService
   *          The Appian Content Service.
   * @param timeoutMs
   *          Timeout in milliseconds for resolving each image.
   * @param placeholderImageUri
   *          The URI string for the placeholder image, or null if none.
   */
  public HTMLImageResolver(ContentService contentService, long timeoutMs, String placeholderImageUri) {
    this.contentService = Objects.requireNonNull(contentService, "ContentService cannot be null");
    this.timeoutMs = timeoutMs;
    this.placeholderImageUri = placeholderImageUri;
  }

  /**
   * A result object to hold both the processed document and any failures.
   */
  public static class ResolutionResult {
    private final Document processedDocument;
    private final List<String> failedImageIds;

    public ResolutionResult(Document processedDocument, List<String> failedImageIds) {
      this.processedDocument = processedDocument;
      this.failedImageIds = failedImageIds;
    }

    public Document getProcessedDocument() {
      return processedDocument;
    }

    public List<String> getFailedImageIds() {
      return failedImageIds;
    }

    public boolean hasFailures() {
      return !failedImageIds.isEmpty();
    }
  }

  /**
   * Parses an HTML string and replaces image sources. It does not throw an exception
   * for individual image failures, instead logging them and optionally using a placeholder.
   *
   * @param htmlString
   *          The raw HTML content.
   * @return A ResolutionResult containing the processed document and a list of failed image IDs.
   */
  public ResolutionResult resolveImagePaths(String htmlString) {
    List<String> failures = new ArrayList<>();
    if (htmlString == null || htmlString.trim().isEmpty()) {
      LOG.warn("Input HTML string is null or empty. Returning an empty document.");
      return new ResolutionResult(Jsoup.parse(""), failures);
    }

    Document htmlDoc = Jsoup.parse(htmlString);
    Elements images = htmlDoc.select(String.format("img[%s], img[%s]", APPIAN_DOC_ID_ATTR, LEGACY_APPIAN_DOC_ID_ATTR));

    LOG.info("Found {} Appian-linked images to resolve with a {}ms timeout.", images.size(), timeoutMs);

    ExecutorService executor = Executors.newSingleThreadExecutor();

    for (Element img : images) {
      String docIdStr = img.hasAttr(APPIAN_DOC_ID_ATTR) ? img.attr(APPIAN_DOC_ID_ATTR) : img.attr(LEGACY_APPIAN_DOC_ID_ATTR);

      Callable<String> downloadTask = () -> getAppianDocumentFilePath(Long.parseLong(docIdStr));
      Future<String> future = executor.submit(downloadTask);

      try {
        String filePath = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        URI fileUri = new File(filePath).toURI();
        img.attr("src", fileUri.toString());
        LOG.debug("Resolved docId {} to path: {}", docIdStr, fileUri);
      } catch (Exception e) {
        future.cancel(true); // Interrupt the task if it's still running
        LOG.warn("Could not resolve image with docId '{}' within the timeout or due to an error. Applying placeholder.", docIdStr, e);
        failures.add(docIdStr);
        if (placeholderImageUri != null) {
          img.attr("src", placeholderImageUri);
        } else {
          img.remove(); // Or remove the image tag entirely if no placeholder
        }
      }
    }

    executor.shutdownNow();
    return new ResolutionResult(htmlDoc, failures);
  }

  private String getAppianDocumentFilePath(long docId) throws AppianException {
    com.appiancorp.suiteapi.knowledge.Document document = (com.appiancorp.suiteapi.knowledge.Document) contentService.download(docId,
      ContentConstants.VERSION_CURRENT, false)[0];
    return document.accessAsReadOnlyFile().getAbsolutePath();
  }
}
