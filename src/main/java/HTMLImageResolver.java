import com.appiancorp.suiteapi.common.exceptions.AppianException;
import com.appiancorp.suiteapi.content.Content;
import com.appiancorp.suiteapi.content.ContentConstants;
import com.appiancorp.suiteapi.content.ContentService;
import com.appiancorp.suiteapi.knowledge.Document;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Resolves <img> tags containing Appian document IDs into file: URIs. Designed for Java 17 and
 * Appian 24.2 SDK compatibility.
 */
@SuppressWarnings({"PMD.DataflowAnomalyAnalysis"})
public class HTMLImageResolver {

  private static final Logger LOG = LogManager.getLogger(HTMLImageResolver.class);
  private static final String APPIAN_DOC_ID_ATTR = "data-docid";
  private static final String LEGACY_APPIAN_DOC_ID_ATTR = "appianDocId";

  private final transient ContentService contentService;
  private final transient long timeoutMs;
  private final transient String placeholderImageUri;
  private final transient int maxThreads;

  public HTMLImageResolver(
      ContentService contentService, long timeoutMs, String placeholderImageUri) {
    this(contentService, timeoutMs, placeholderImageUri, 4);
  }

  public HTMLImageResolver(
      ContentService contentService, long timeoutMs, String placeholderImageUri, int maxThreads) {
    this.contentService = Objects.requireNonNull(contentService, "ContentService cannot be null");
    this.timeoutMs = timeoutMs > 0 ? timeoutMs : 5000;
    this.placeholderImageUri = placeholderImageUri;
    this.maxThreads = Math.max(1, maxThreads);
  }

  public ResolutionResult resolveImagePaths(String htmlString) {
    List<String> failures = new ArrayList<>();
    if (htmlString == null || htmlString.trim().isEmpty()) {
      LOG.warn("Input HTML string is null or empty; returning an empty document.");
      return new ResolutionResult(Jsoup.parse(""), failures);
    }

    org.jsoup.nodes.Document htmlDoc = Jsoup.parse(htmlString);
    Elements images =
        htmlDoc.select("img[" + APPIAN_DOC_ID_ATTR + "], img[" + LEGACY_APPIAN_DOC_ID_ATTR + "]");

    if (images.isEmpty()) {
      LOG.debug("No Appian-linked images found in HTML.");
      return new ResolutionResult(htmlDoc, failures);
    }

    final int threads = Math.max(1, Math.min(images.size(), maxThreads));
    final ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      final List<Future<Void>> futures = new ArrayList<>();
      for (Element img : images) {
        String docIdStr =
            img.hasAttr(APPIAN_DOC_ID_ATTR)
                ? img.attr(APPIAN_DOC_ID_ATTR)
                : img.attr(LEGACY_APPIAN_DOC_ID_ATTR);

        if (!docIdStr.matches("\\d+")) {
          LOG.warn("Invalid docId attribute '{}'. Skipping image.", docIdStr);
          failures.add(docIdStr);
          applyPlaceholder(img);
          continue;
        }

        final long docId = Long.parseLong(docIdStr);
        futures.add(
            executor.submit(
                new Callable<Void>() {
                  @Override
                  public Void call() {
                    resolveSingleImage(img, docId, failures);
                    return null;
                  }
                }));
      }

      for (Future<Void> f : futures) {
        try {
          f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
          LOG.error("Timed out resolving an image after {} ms", timeoutMs);
        } catch (ExecutionException e) {
          LOG.error(
              "Error resolving image: {}",
              e.getCause() != null ? e.getCause().getMessage() : e.getMessage(),
              e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          LOG.error("Interrupted while resolving images", e);
        }
      }
    } finally {
      executor.shutdownNow();
    }

    return new ResolutionResult(htmlDoc, failures);
  }

  private void resolveSingleImage(Element img, long docId, List<String> failures) {
    try {
      Content[] contents = contentService.download(docId, ContentConstants.VERSION_CURRENT, false);
      if (contents == null || contents.length == 0 || !(contents[0] instanceof Document)) {
        throw new AppianException("Content not found or is not a Document for id=" + docId);
      }
      Document doc = (Document) contents[0];
      File tmp = doc.accessAsReadOnlyFile();
      URI fileUri = tmp.toURI();

      img.attr("src", fileUri.toString());
      img.removeAttr(APPIAN_DOC_ID_ATTR);
      img.removeAttr(LEGACY_APPIAN_DOC_ID_ATTR);
    } catch (Exception e) {
      LOG.warn("Failed to resolve image for docId {}: {}", docId, e.getMessage());
      failures.add(String.valueOf(docId));
      applyPlaceholder(img);
    }
  }

  private void applyPlaceholder(Element img) {
    if (placeholderImageUri != null && !placeholderImageUri.isBlank()) {
      img.attr("src", placeholderImageUri);
    }
  }

  public static final class ResolutionResult {
    private final org.jsoup.nodes.Document processedDocument;
    private final List<String> failedImageIds;

    public ResolutionResult(
        org.jsoup.nodes.Document processedDocument, List<String> failedImageIds) {
      this.processedDocument = Objects.requireNonNull(processedDocument);
      this.failedImageIds = new ArrayList<>(Objects.requireNonNull(failedImageIds));
    }

    public org.jsoup.nodes.Document getProcessedDocument() {
      return processedDocument;
    }

    public List<String> getFailedImageIds() {
      return Collections.unmodifiableList(failedImageIds);
    }

    public boolean hasFailures() {
      return !failedImageIds.isEmpty();
    }
  }
}
