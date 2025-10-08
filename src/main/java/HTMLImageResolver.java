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

    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Found {} Appian-linked images to resolve (timeout={} ms).", images.size(), timeoutMs);
    }

      ExecutorService executor = null;
      try {
          executor = Executors.newFixedThreadPool(Math.min(images.size(), maxThreads));
          List<Future<?>> futures = new ArrayList<>();

          for (String imageUrl : images) {
              futures.add(executor.submit(() -> downloadImage(imageUrl)));
          }

          for (Future<?> future : futures) {
              try {
                  future.get();
              } catch (ExecutionException e) {
                  LOG.error("Error downloading image", e.getCause());
              }
          }

      } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          if (LOG.isErrorEnabled()) {
              LOG.error("Image processing interrupted", e);
          }
      } finally {
          if (executor != null && !executor.isShutdown()) {
              executor.shutdown();
              try {
                  if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                      executor.shutdownNow();
                  }
              } catch (InterruptedException ie) {
                  executor.shutdownNow();
                  Thread.currentThread().interrupt();
              }
          }
      }
      try {
      List<Future<Void>> futures = new ArrayList<>();
      for (Element img : images) {
        String docIdStr =
            img.hasAttr(APPIAN_DOC_ID_ATTR)
                ? img.attr(APPIAN_DOC_ID_ATTR)
                : img.attr(LEGACY_APPIAN_DOC_ID_ATTR);
        if (!docIdStr.matches("\\d+")) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Invalid docId attribute '{}'. Skipping image.", docIdStr);
            }
          failures.add(docIdStr);
          applyPlaceholder(img);
          continue;
        }
        long docId = Long.parseLong(docIdStr);
        Callable<Void> task =
            () -> {
              try {
                String filePath = getAppianDocumentFilePath(docId);
                URI fileUri = new File(filePath).toURI();
                synchronized (img) {
                  img.attr("src", fileUri.toString());
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Resolved docId {} to {}", docId, fileUri);
                }
              } catch (AppianException e) {
                  if (LOG.isWarnEnabled()) {
                      LOG.warn("Failed to resolve docId {}. Applying placeholder.", docId, e);
                  }
                failures.add(String.valueOf(docId));
                applyPlaceholder(img);
              }
              return null;
            };
        futures.add(executor.submit(task));
      }
      // Await completion or timeout
      for (Future<Void> f : futures) {
        try {
          f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
          f.cancel(true);
            if (LOG.isWarnEnabled()) {
                LOG.warn("Image resolution task failed or timed out.", e);
            }
        }
      }
    } finally {
      executor.shutdownNow();
    }
    return new ResolutionResult(htmlDoc, failures);
  }

  private String getAppianDocumentFilePath(long docId) throws AppianException {
    synchronized (contentService) {
      Content[] contents = contentService.download(docId, ContentConstants.VERSION_CURRENT, false);
      if (contents.length == 0 || !(contents[0] instanceof Document)) {
        throw new AppianException("Invalid content returned for docId " + docId);
      }
      Document document = (Document) contents[0];
      return document.accessAsReadOnlyFile().getAbsolutePath();
    }
  }

  private void applyPlaceholder(Element img) {
    if (placeholderImageUri != null && !placeholderImageUri.isBlank()) {
      img.attr("src", placeholderImageUri);
    } else {
      img.remove();
    }
  }

  /** Encapsulates the result of image resolution. */
  public static class ResolutionResult {
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
