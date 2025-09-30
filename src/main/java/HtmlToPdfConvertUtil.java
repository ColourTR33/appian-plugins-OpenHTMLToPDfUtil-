import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.helper.W3CDom;

import com.appiancorp.suiteapi.common.Name;
import com.appiancorp.suiteapi.common.exceptions.AppianException;
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

@PaletteInfo(paletteCategory = "Appian Smart Services", palette = "Document Generation")
@Order({ "SourceDocument", "NewDocumentName", "NewDocumentDesc", "SaveInFolder", "Width", "Height", "PlaceholderImage",
  "ImageResolutionTimeout" })
public class HtmlToPdfConvertUtil extends AppianSmartService {

  private static final Logger LOG = LogManager.getLogger(HtmlToPdfConvertUtil.class);

  // Inputs
  private Long sourceDocument;
  private String newDocumentName;
  private String newDocumentDesc;
  private Long saveInFolder;
  private Integer width;
  private Integer height;
  private Long placeholderImage;
  private Long imageResolutionTimeout; // Timeout in milliseconds

  // Outputs
  private Long newDocumentCreated;
  private boolean errorOccurred;
  private String errorMessage;

  private final ContentService cs;

  public HtmlToPdfConvertUtil(ContentService cs) {
    super();
    this.cs = cs;
  }

  @Override
  public void run() throws SmartServiceException {
    File tempPdfFile = null;
    try {
      // --- PREPARATION ---
      String placeholderUri = null;
      if (this.placeholderImage != null && this.placeholderImage > 0) {
        try {
          LOG.info("Resolving placeholder image with ID: {}", this.placeholderImage);
          Document placeholderDoc = (Document) cs.download(this.placeholderImage, ContentConstants.VERSION_CURRENT, false)[0];
          placeholderUri = new File(placeholderDoc.accessAsReadOnlyFile().getAbsolutePath()).toURI().toString();
        } catch (AppianException e) {
          handleException(e, "The specified placeholder image could not be accessed. Please check its ID and security.");
          return;
        }
      }

      // --- EXECUTION ---
      LOG.info("Downloading HTML document with the ID: {}", sourceDocument);
      Document sourceDoc = (Document) cs.download(sourceDocument, ContentConstants.VERSION_CURRENT, false)[0];
      String htmlContent = FileUtils.readFileToString(sourceDoc.accessAsReadOnlyFile(), "UTF-8");

      HTMLImageResolver imageResolver = new HTMLImageResolver(cs, this.imageResolutionTimeout, placeholderUri);
      HTMLImageResolver.ResolutionResult result = imageResolver.resolveImagePaths(htmlContent);
      org.jsoup.nodes.Document resolvedHtmlDoc = result.getProcessedDocument();

      if (result.hasFailures()) {
        LOG.warn("The following image document IDs failed to resolve and were replaced: {}",
          String.join(", ", result.getFailedImageIds()));
      }

      resolvedHtmlDoc.body().attr("style", "word-wrap: break-word;");
      LOG.info("Resolved image paths and applied final styles to HTML.");

      tempPdfFile = File.createTempFile("temp_html_to_pdf_", ".pdf");
      LOG.info("Created temporary file for PDF output: {}", tempPdfFile.getAbsolutePath());
      try (OutputStream os = new FileOutputStream(tempPdfFile)) {
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.useDefaultPageSize(width, height, PageSizeUnits.MM);
        builder.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
        builder.useUnicodeBidiReorderer(new ICUBidiReorderer());

        // ** NO CHANGE NEEDED HERE **
        // The fromJsoup() method returns an org.w3c.dom.Document, which is what withW3cDocument() expects.
        // The invalid alias was not needed in the first place.
        builder.withW3cDocument(new W3CDom().fromJsoup(resolvedHtmlDoc), sourceDoc.getInternalFilename());

        builder.toStream(os);
        builder.run();
      }
      LOG.info("Successfully rendered PDF to temporary file.");

      newDocumentCreated = createAndUploadDocument(tempPdfFile);
      LOG.info("Successfully created and uploaded new Appian document with ID: {}", newDocumentCreated);

    } catch (AppianException e) {
      handleException(e, "An Appian API error occurred: " + e.getMessage());
    } catch (IOException e) {
      handleException(e, "A file I/O error occurred: " + e.getMessage());
    } catch (Exception e) {
      handleException(e, "An unexpected error occurred: " + e.getMessage());
    } finally {
      if (tempPdfFile != null) {
        if (tempPdfFile.delete()) {
          LOG.info("Successfully deleted temporary file: {}", tempPdfFile.getName());
        } else {
          LOG.warn("Failed to delete temporary file: {}", tempPdfFile.getName());
        }
      }
    }
  }

  private Long createAndUploadDocument(File sourceFile) throws AppianException, IOException {
    Document docToCreate = new Document();
    docToCreate.setName(newDocumentName.trim());
    docToCreate.setDescription(newDocumentDesc != null ? newDocumentDesc.trim() : "");
    docToCreate.setExtension("pdf");
    docToCreate.setParent(saveInFolder);

    Long docId = cs.create(docToCreate, ContentConstants.UNIQUE_NONE);
    LOG.debug("Created document placeholder with ID: {}", docId);

    Document newDoc = (Document) cs.download(docId, ContentConstants.VERSION_CURRENT, false)[0];
    try (OutputStream os = newDoc.getOutputStream();
      FileInputStream fis = new FileInputStream(sourceFile)) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = fis.read(buffer)) != -1) {
        os.write(buffer, 0, bytesRead);
      }
    }
    return cs.getVersion(docId, ContentConstants.VERSION_CURRENT).getId();
  }

  private void handleException(Exception e, String userFriendlyMessage) throws SmartServiceException {
    LOG.error(userFriendlyMessage, e);
    this.errorOccurred = true;
    this.errorMessage = userFriendlyMessage;
    throw new SmartServiceException.Builder(getClass(), e).userMessage(userFriendlyMessage).build();
  }

  public void onSave(MessageContainer messages) {
  }

  public void validate(MessageContainer msg) {
    if (sourceDocument == null || sourceDocument <= 0) {
      msg.addError("SourceDocument", "A valid source document is required.");
    } else {
      try {
        Document doc = (Document) cs.download(sourceDocument, ContentConstants.VERSION_CURRENT, false)[0];
        if (!"html".equalsIgnoreCase(doc.getExtension())) {
          msg.addError("SourceDocument", "Source document must be an HTML file.");
        }
      } catch (Exception e) {
        msg.addError("SourceDocument", "Invalid or inaccessible source document: " + e.getMessage());
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

  // --- Getters and Setters for Inputs and Outputs ---
  @Input(required = Required.ALWAYS)
  @Name("SourceDocument")
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
