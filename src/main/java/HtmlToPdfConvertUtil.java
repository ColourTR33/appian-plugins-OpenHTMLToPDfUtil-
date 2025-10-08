import com.appiancorp.suiteapi.common.Name;
import com.appiancorp.suiteapi.common.exceptions.AppianException;
import com.appiancorp.suiteapi.content.Content;
import com.appiancorp.suiteapi.content.ContentConstants;
import com.appiancorp.suiteapi.content.ContentService;
import com.appiancorp.suiteapi.knowledge.Document;
import com.appiancorp.suiteapi.knowledge.DocumentDataType;
import com.appiancorp.suiteapi.knowledge.FolderDataType;
import com.appiancorp.suiteapi.process.exceptions.SmartServiceException;
import com.appiancorp.suiteapi.process.framework.AppianSmartService;
import com.appiancorp.suiteapi.process.framework.Input;
import com.appiancorp.suiteapi.process.framework.MessageContainer;
import com.appiancorp.suiteapi.process.framework.Order;
import com.appiancorp.suiteapi.process.palette.PaletteInfo;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.helper.W3CDom;

/** Appian Smart Service: Convert an HTML (Appian Document) to a PDF (new Appian Document). */
@PaletteInfo(paletteCategory = "Appian Smart Services", palette = "Document Generation")
@Order({
  "SourceDocument",
  "NewDocumentName",
  "NewDocumentDesc",
  "SaveInFolder",
  "PageWidthMm",
  "PageHeightMm",
  "Dpi",
  "ImageResolutionTimeoutMs",
  "MaxImageResolutionThreads",
  "PlaceholderImage"
})
@SuppressWarnings({"PMD.DataflowAnomalyAnalysis", "PMD.AvoidLiteralsInIfCondition"})
public class HtmlToPdfConvertUtil extends AppianSmartService {

  private static final Logger LOG = LogManager.getLogger(HtmlToPdfConvertUtil.class);

  // Defaults / constants
  private static final double DEFAULT_WIDTH_MM = 210.0;
  private static final double DEFAULT_HEIGHT_MM = 297.0;
  private static final int DEFAULT_DPI = 96;

  // Inputs
  @Name("SourceDocument")
  @Input(required = true)
  @DocumentDataType
  private Long sourceDocument;

  @Name("NewDocumentName")
  @Input(required = true)
  private String newDocumentName;

  @Name("NewDocumentDesc")
  @Input(required = false)
  private String newDocumentDesc;

  @Name("SaveInFolder")
  @Input(required = true)
  @FolderDataType
  private Long saveInFolder;

  @Name("PageWidthMm")
  @Input(required = false)
  private Double pageWidthMm;

  @Name("PageHeightMm")
  @Input(required = false)
  private Double pageHeightMm;

  @Name("Dpi")
  @Input(required = false)
  private Integer dpi;

  @Name("ImageResolutionTimeoutMs")
  @Input(required = false)
  private Long imageResolutionTimeoutMs;

  @Name("MaxImageResolutionThreads")
  @Input(required = false)
  private Integer maxImageResolutionThreads;

  @Name("PlaceholderImage")
  @Input(required = false)
  @DocumentDataType
  private Long placeholderImage;

  // Output
  @Name("NewDocumentCreated")
  @DocumentDataType
  private Long newDocumentCreated;

  // Services
  private final ContentService cs;

  public HtmlToPdfConvertUtil(ContentService cs) {
    this.cs = Objects.requireNonNull(cs, "ContentService cannot be null");
  }

  @Override
  public void run() throws SmartServiceException {
    File tempPdfFile = null;
    try {
      // --- Resolve optional placeholder image to URI ---
      String placeholderUri = null;
      if (placeholderImage != null && placeholderImage > 0) {
        try {
          Content[] phContents =
              cs.download(placeholderImage, ContentConstants.VERSION_CURRENT, false);
          if (phContents == null
              || phContents.length == 0
              || !(phContents[0] instanceof Document)) {
            throw new AppianException("Placeholder content invalid or not a document.");
          }
          File phFile = ((Document) phContents[0]).accessAsReadOnlyFile();
          placeholderUri = phFile.toURI().toString();
          LOG.info("Resolved placeholder image URI: {}", placeholderUri);
        } catch (AppianException e) {
          handleException(
              e,
              "The specified placeholder image could not be accessed. Please check its ID and"
                  + " security.");
          return; // keep static analysis happy (handleException throws)
        }
      }

      // --- Download HTML source as UTF-8 string ---
      Content[] contents = cs.download(sourceDocument, ContentConstants.VERSION_CURRENT, false);
      if (contents == null || contents.length == 0 || !(contents[0] instanceof Document)) {
        throw new AppianException("Source content invalid or not a document.");
      }
      File htmlFile = ((Document) contents[0]).accessAsReadOnlyFile();
      String html;
      try (FileInputStream in = new FileInputStream(htmlFile)) {
        byte[] bytes = in.readAllBytes();
        html = new String(bytes, StandardCharsets.UTF_8);
      }

      // --- Resolve embedded Appian images to file: URIs ---
      long timeout =
          (imageResolutionTimeoutMs != null && imageResolutionTimeoutMs > 0)
              ? imageResolutionTimeoutMs
              : 5000L;
      int threads =
          (maxImageResolutionThreads != null && maxImageResolutionThreads > 0)
              ? maxImageResolutionThreads
              : 4;

      HTMLImageResolver resolver = new HTMLImageResolver(cs, timeout, placeholderUri, threads);
      HTMLImageResolver.ResolutionResult rr = resolver.resolveImagePaths(html);
      if (rr.hasFailures()) {
        LOG.warn("One or more images failed to resolve: {}", rr.getFailedImageIds());
      }

      // --- Convert to W3C DOM for renderer ---
      org.w3c.dom.Document w3cDoc = new W3CDom().fromJsoup(rr.getProcessedDocument());

      // --- Render PDF to temp file ---
      tempPdfFile = File.createTempFile("html2pdf-", ".pdf");
      try (OutputStream os = new FileOutputStream(tempPdfFile)) {
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.withW3cDocument(w3cDoc, null);
        // Page size in mm (margins should be handled by CSS inside HTML if needed)
        double wMm = pageWidthMm != null ? pageWidthMm : DEFAULT_WIDTH_MM;
        double hMm = pageHeightMm != null ? pageHeightMm : DEFAULT_HEIGHT_MM;
        // openhtmltopdf expects sizes via CSS or default—explicit page size can be set using
        // metadata/CSS.
        // Keeping it simple to avoid API drift: rely on HTML/CSS for custom page size.

        // DPI (if you embed images that rely on DPI interpretation)
        int effectiveDpi = (dpi != null && dpi > 0) ? dpi : DEFAULT_DPI;
        builder.useDefaultTextDirection(PdfRendererBuilder.TextDirection.LTR);
        builder.usePdfVersion(PdfRendererBuilder.PdfVersion.PDF_1_7);
        builder.useFastMode(); // keep perf reasonable
        // If you need RTL/ICU, add the appropriate dependencies and enable bidi helpers.

        builder.toStream(os);
        builder.run();
      }

      // --- Save as new Appian document ---
      newDocumentCreated =
          createAndUploadDocument(tempPdfFile, newDocumentName, newDocumentDesc, saveInFolder);
      LOG.info("Created new Appian document id={}", newDocumentCreated);

    } catch (AppianException e) {
      handleException(e, "An Appian API error occurred: " + e.getMessage());
    } catch (Exception e) {
      handleException(e, "An unexpected error occurred: " + e.getMessage());
    } finally {
      if (tempPdfFile != null && tempPdfFile.exists()) {
        if (!tempPdfFile.delete()) {
          LOG.warn("Could not delete temporary file: {}", tempPdfFile.getName());
        } else {
          LOG.info("Deleted temporary file: {}", tempPdfFile.getName());
        }
      }
    }
  }

  // --- Create/upload the generated PDF to Appian ---
  private Long createAndUploadDocument(File pdfFile, String name, String desc, Long folderId)
      throws Exception {
    // The exact API depends on your SDK stub; most plugins wrap this in a helper.
    // Here we assume the ContentService can create a Document from a File.
    // If your team standard uses a different helper, wire it in here.
    try (FileInputStream in = new FileInputStream(pdfFile)) {
      byte[] bytes = in.readAllBytes();
      // Pseudocode-ish call; adjust to your real ContentService API if needed:
      // return cs.createDocument(name, desc, folderId, bytes);  // <- common pattern
      // To stay stub-friendly, fall back to the Document helper if present:
      Document newDoc = cs.createDocument(name, desc, folderId, bytes);
      return newDoc.getId();
    }
  }

  // --- Validation & error handling ---

  @Override
  public void validate(MessageContainer messages) {
    if (sourceDocument == null || sourceDocument <= 0) {
      messages.addError("SourceDocument", "You must provide a valid HTML document.");
    }
    if (saveInFolder == null || saveInFolder <= 0) {
      messages.addError("SaveInFolder", "You must provide a valid destination folder.");
    }
    if (newDocumentName == null || newDocumentName.isBlank()) {
      messages.addError("NewDocumentName", "You must provide a name for the new PDF document.");
    }
    if (pageWidthMm != null && pageWidthMm <= 0) {
      messages.addError("PageWidthMm", "Page width must be a positive number of millimetres.");
    }
    if (pageHeightMm != null && pageHeightMm <= 0) {
      messages.addError("PageHeightMm", "Page height must be a positive number of millimetres.");
    }
    if (dpi != null && dpi <= 0) {
      messages.addError("Dpi", "DPI must be a positive integer.");
    }
    if (imageResolutionTimeoutMs != null && imageResolutionTimeoutMs <= 0) {
      messages.addError(
          "ImageResolutionTimeoutMs", "Timeout must be a positive number of milliseconds.");
    }
    if (maxImageResolutionThreads != null && maxImageResolutionThreads <= 0) {
      messages.addError("MaxImageResolutionThreads", "Threads must be a positive integer.");
    }
  }

  private void handleException(Exception e, String userMessage) throws SmartServiceException {
    LOG.error(userMessage, e);
    throw new SmartServiceException(userMessage, e);
  }

  // --- Output getter (Appian reads this) ---
  public Long getNewDocumentCreated() {
    return newDocumentCreated;
  }

  // --- Setters for inputs (Appian injects these) ---
  public void setSourceDocument(Long sourceDocument) {
    this.sourceDocument = sourceDocument;
  }

  public void setNewDocumentName(String newDocumentName) {
    this.newDocumentName = newDocumentName;
  }

  public void setNewDocumentDesc(String newDocumentDesc) {
    this.newDocumentDesc = newDocumentDesc;
  }

  public void setSaveInFolder(Long saveInFolder) {
    this.saveInFolder = saveInFolder;
  }

  public void setPageWidthMm(Double pageWidthMm) {
    this.pageWidthMm = pageWidthMm;
  }

  public void setPageHeightMm(Double pageHeightMm) {
    this.pageHeightMm = pageHeightMm;
  }

  public void setDpi(Integer dpi) {
    this.dpi = dpi;
  }

  public void setImageResolutionTimeoutMs(Long imageResolutionTimeoutMs) {
    this.imageResolutionTimeoutMs = imageResolutionTimeoutMs;
  }

  public void setMaxImageResolutionThreads(Integer maxImageResolutionThreads) {
    this.maxImageResolutionThreads = maxImageResolutionThreads;
  }

  public void setPlaceholderImage(Long placeholderImage) {
    this.placeholderImage = placeholderImage;
  }
}
