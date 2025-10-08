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
import com.appiancorp.suiteapi.process.framework.Required;
import com.appiancorp.suiteapi.process.palette.PaletteInfo;
import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.PageSizeUnits;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.helper.W3CDom;

/**
 * Appian Smart Service: Convert an HTML Appian Document to PDF and save as a new Appian Document.
 *
 * <p>Notes: - Uses the Appian ContentService stub (compileOnly) as provided in your build.gradle. -
 * Carefully checks ContentService.download() results and handles I/O resources safely.
 */
@PaletteInfo(paletteCategory = "Appian Smart Services", palette = "Document Generation")
@Order({
  "SourceDocument",
  "NewDocumentName",
  "NewDocumentDesc",
  "SaveInFolder",
  "Width",
  "Height",
  "PlaceholderImage",
  "ImageResolutionTimeout"
})
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "ContentService is framework injected; not defensively copied intentionally.")
public class HtmlToPdfConvertUtil extends AppianSmartService {

  private static final Logger LOG = LogManager.getLogger(HtmlToPdfConvertUtil.class);

  private final transient ContentService cs;
  private static final transient String SOURCE_DOCUMENT = "SourceDocument";

  // Inputs (names kept consistent with your original Smart Service)
  private transient Long sourceDocument;
  private transient String newDocumentName;
  private transient String newDocumentDesc;
  private transient Long saveInFolder;
  private transient Integer width;
  private transient Integer height;
  private transient Long placeholderImage;
  private transient Long imageResolutionTimeout; // milliseconds

  // Outputs
  private transient Long newDocumentCreated;
  private transient boolean errorOccurred;
  private transient String errorMessage;

  public HtmlToPdfConvertUtil(ContentService cs) {
    super();
    this.cs = cs;
  }

  @Override
  public void run() throws SmartServiceException {
    File tempPdfFile = null;
    try {
      // --- Resolve placeholder image (if provided) ---
      String placeholderUri = null;
      if (this.placeholderImage != null && this.placeholderImage > 0) {
        try {
          Content[] placeholderContents =
              cs.download(this.placeholderImage, ContentConstants.VERSION_CURRENT, false);
          if (placeholderContents == null
              || placeholderContents.length == 0
              || !(placeholderContents[0] instanceof Document)) {
            throw new AppianException("Placeholder content invalid or not a document.");
          }
          Document placeholderDoc = (Document) placeholderContents[0];
          File placeholderFile = placeholderDoc.accessAsReadOnlyFile();
          placeholderUri = placeholderFile.toURI().toString();
          LOG.info("Resolved placeholder image URI: {}", placeholderUri);
        } catch (AppianException e) {
          handleException(
              e,
              "The specified placeholder image could not be accessed. Please check its ID and"
                  + " security.");
          return; // handleException will throw SmartServiceException, but return is defensive
        }
      }

      // --- Download source HTML document ---
      if (LOG.isInfoEnabled()) {
        LOG.info("Downloading HTML document with the ID: {}", sourceDocument);
      }
      Content[] sourceContents =
          cs.download(sourceDocument, ContentConstants.VERSION_CURRENT, false);
      if (sourceContents == null
          || sourceContents.length == 0
          || !(sourceContents[0] instanceof Document)) {
        throw new AppianException("Source document invalid or not found: " + sourceDocument);
      }
      Document sourceDoc = (Document) sourceContents[0];

      // Read HTML content (explicit UTF-8)
      String htmlContent =
          FileUtils.readFileToString(sourceDoc.accessAsReadOnlyFile(), StandardCharsets.UTF_8);

      // Resolve image references inside HTML using your HTMLImageResolver (assumed available in
      // classpath)
      long timeoutMs =
          (imageResolutionTimeout != null && imageResolutionTimeout > 0)
              ? imageResolutionTimeout
              : 5000L; // reasonable default
      HTMLImageResolver imageResolver = new HTMLImageResolver(cs, timeoutMs, placeholderUri);
      HTMLImageResolver.ResolutionResult result = imageResolver.resolveImagePaths(htmlContent);

      org.jsoup.nodes.Document resolvedHtmlDoc = result.getProcessedDocument();

      if (result.hasFailures()) {
        if (LOG.isWarnEnabled()) {
          LOG.warn(
              "Some image docIds failed to resolve: {}",
              String.join(", ", result.getFailedImageIds()));
        }
      }

      // Final styling/housekeeping
      resolvedHtmlDoc.body().attr("style", "word-wrap: break-word;");

      // --- Render to PDF ---
      tempPdfFile = File.createTempFile("temp_html_to_pdf_", ".pdf");
      if (LOG.isInfoEnabled()) {
        LOG.info("Temporary PDF file: {}", tempPdfFile.getAbsolutePath());
      }

      try (OutputStream os = new FileOutputStream(tempPdfFile)) {
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        // width/height are expected in mm (per your earlier code)
        builder.useDefaultPageSize(
            width != null ? width : 210, height != null ? height : 297, PageSizeUnits.MM);
        builder.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
        builder.useUnicodeBidiReorderer(new ICUBidiReorderer());

        // Convert Jsoup document to W3C DOM for OpenHTMLToPDF
        org.w3c.dom.Document w3cDoc = new W3CDom().fromJsoup(resolvedHtmlDoc);
        // Use source document's internal filename as the baseUri if available
        String baseUri =
            sourceDoc.getInternalFilename() != null ? sourceDoc.getInternalFilename() : null;
        builder.withW3cDocument(w3cDoc, baseUri);

        builder.toStream(os);
        builder.run();
      }
      if (LOG.isInfoEnabled()) {
        LOG.info("Rendered PDF to temporary file successfully.");
      }

      // --- Create an Appian Document and upload the PDF bytes ---
      newDocumentCreated = createAndUploadDocument(tempPdfFile);
      if (LOG.isInfoEnabled()) {
        LOG.info("Created new Appian document id={}", newDocumentCreated);
      }

    } catch (AppianException e) {
      handleException(e, "An Appian API error occurred: " + e.getMessage());
    } catch (IOException e) {
      handleException(e, "A file I/O error occurred: " + e.getMessage());
    } catch (Exception e) {
      handleException(e, "An unexpected error occurred: " + e.getMessage());
    } finally {
      if (tempPdfFile != null && tempPdfFile.exists()) {
        if (tempPdfFile.delete()) {
          if (LOG.isInfoEnabled()) {
            LOG.info("Deleted temporary file: {}", tempPdfFile.getName());
          }
        } else {
          if (LOG.isWarnEnabled()) {
            LOG.warn("Could not delete temporary file: {}", tempPdfFile.getName());
          }
        }
      }
    }
  }

  /**
   * Creates a placeholder Appian document, writes the provided file bytes into it, and returns the
   * document version id.
   */
  private Long createAndUploadDocument(File sourceFile) throws AppianException, IOException {
    Document docToCreate = new Document();
    docToCreate.setName(newDocumentName != null ? newDocumentName.trim() : "converted.pdf");
    docToCreate.setDescription(newDocumentDesc != null ? newDocumentDesc.trim() : "");
    docToCreate.setExtension("pdf");
    docToCreate.setParent(saveInFolder);

    Long docId = cs.create(docToCreate, ContentConstants.UNIQUE_NONE);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Created document placeholder with ID: {}", docId);
    }

    Content[] createdContents = cs.download(docId, ContentConstants.VERSION_CURRENT, false);
    if (createdContents == null
        || createdContents.length == 0
        || !(createdContents[0] instanceof Document)) {
      throw new AppianException("Could not obtain created document for upload: " + docId);
    }
    Document newDoc = (Document) createdContents[0];

    try (OutputStream os = newDoc.getOutputStream();
        FileInputStream fis = new FileInputStream(sourceFile)) {
      byte[] buffer = new byte[8192];
      int read;
      while (true) {
        read = fis.read(buffer);
        if (read == -1) break;
        os.write(buffer, 0, read);
      }
      os.flush();
    }

    // Return the version id of the current (latest) version
    return cs.getVersion(docId, ContentConstants.VERSION_CURRENT).getId();
  }

  private void handleException(Exception e, String userFriendlyMessage)
      throws SmartServiceException {
    if (LOG.isErrorEnabled()) {
      LOG.error(userFriendlyMessage, e);
    }
    this.errorOccurred = true;
    this.errorMessage = userFriendlyMessage;
    throw new SmartServiceException.Builder(getClass(), e).userMessage(userFriendlyMessage).build();
  }

  // --- Validation ---

  @Override
  public void validate(MessageContainer msg) {
    if (sourceDocument == null || sourceDocument <= 0) {
      msg.addError(SOURCE_DOCUMENT, "A valid source document is required.");
    } else {
      try {
        Content[] contents = cs.download(sourceDocument, ContentConstants.VERSION_CURRENT, false);
        if (contents == null || contents.length == 0 || !(contents[0] instanceof Document)) {
          msg.addError(SOURCE_DOCUMENT, "Source document not found or is invalid.");
        } else {
          Document doc = (Document) contents[0];
          if (!"html".equalsIgnoreCase(doc.getExtension())) {
            msg.addError(SOURCE_DOCUMENT, "Source document must be an HTML file.");
          }
        }
      } catch (Exception e) {
        msg.addError(SOURCE_DOCUMENT, "Invalid or inaccessible source document: " + e.getMessage());
      }
    }

    if (newDocumentName == null || newDocumentName.trim().isEmpty()) {
      msg.addError("NewDocumentName", "A name for the new document is required.");
    }

    if (saveInFolder == null || saveInFolder <= 0) {
      msg.addError("SaveInFolder", "A valid folder to save the document in is required.");
    }

    if (width == null || width <= 0) {
      msg.addError("Width", "A positive width in mm is required.");
    }

    if (height == null || height <= 0) {
      msg.addError("Height", "A positive height in mm is required.");
    }
  }

  // --- Getters and Setters for Inputs / Outputs ---

  @Input(required = Required.ALWAYS)
  @Name(SOURCE_DOCUMENT)
  @DocumentDataType
  public void setSourceDocument(Long val) {
    this.sourceDocument = val;
  }

  @Input(required = Required.ALWAYS)
  @Name("NewDocumentName")
  public void setNewDocumentName(String val) {
    this.newDocumentName = val;
  }

  @Input(required = Required.OPTIONAL)
  @Name("NewDocumentDesc")
  public void setNewDocumentDesc(String val) {
    this.newDocumentDesc = val;
  }

  @Input(required = Required.ALWAYS)
  @Name("SaveInFolder")
  @FolderDataType
  public void setSaveInFolder(Long val) {
    this.saveInFolder = val;
  }

  @Input(required = Required.ALWAYS, defaultValue = "210")
  @Name("Width")
  public void setWidth(Integer val) {
    this.width = val;
  }

  @Input(required = Required.ALWAYS, defaultValue = "297")
  @Name("Height")
  public void setHeight(Integer val) {
    this.height = val;
  }

  @Input(required = Required.OPTIONAL)
  @Name("PlaceholderImage")
  @DocumentDataType
  public void setPlaceholderImage(Long val) {
    this.placeholderImage = val;
  }

  @Input(required = Required.OPTIONAL, defaultValue = "5000")
  @Name("ImageResolutionTimeout")
  public void setImageResolutionTimeout(Long val) {
    this.imageResolutionTimeout = val;
  }

  @Name("NewDocumentCreated")
  @DocumentDataType
  public Long getNewDocumentCreated() {
    return newDocumentCreated;
  }

  @Name("errorOccurred")
  public boolean isErrorOccurred() {
    return errorOccurred;
  }

  @Name("errorMessage")
  public String getErrorMessage() {
    return errorMessage;
  }
}
