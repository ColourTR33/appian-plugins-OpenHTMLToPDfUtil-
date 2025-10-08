import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.util.Matrix;

public class PDFPageNumberer {

  private static final Logger LOG = LogManager.getLogger(PDFPageNumberer.class);
  private static final int ROTATION_90 = 90;
  private static final int ROTATION_270 = 270;

  private final transient PageNumberingOptions options;

  public PDFPageNumberer(PageNumberingOptions options) {
    this.options = Objects.requireNonNull(options, "PageNumberingOptions cannot be null");
  }

  public void addPageNumbers(File pdfFile, int startPage, float xOffset, float yOffset)
      throws IOException, IllegalArgumentException {

    Objects.requireNonNull(pdfFile, "PDF file cannot be null");
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Starting to add page numbers to '{}' with options: [Font={}, Size={}, Format='{}']",
          pdfFile.getName(),
          options.getFont().getName(),
          options.getFontSize(),
          options.getPageFormat());
    }

    try (PDDocument doc = PDDocument.load(pdfFile)) {
      if (startPage > doc.getNumberOfPages() || startPage < 1) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid start page: %d. Document only has %d pages.",
                startPage, doc.getNumberOfPages()));
      }

      for (int i = startPage - 1; i < doc.getNumberOfPages(); i++) {
        PDPage page = doc.getPage(i);
        int currentPageNumber = i + 1;
        String text = formatPageNumberText(currentPageNumber, doc.getNumberOfPages());
        addTextToPage(doc, page, text, xOffset, yOffset);
      }

      doc.save(pdfFile);
      if (LOG.isInfoEnabled()) {
        LOG.info("Successfully added page numbers and saved the document.");
      }
    }
  }

  private void addTextToPage(PDDocument doc, PDPage page, String text, float xOffset, float yOffset)
      throws IOException {
    try (PDPageContentStream contentStream =
        new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {
      contentStream.beginText();
      contentStream.setFont(options.getFont(), options.getFontSize());

      int rotation = page.getRotation();
      if (rotation == ROTATION_90) {
        contentStream.setTextMatrix(
            Matrix.getRotateInstance(
                Math.toRadians(ROTATION_90), page.getMediaBox().getUpperRightX(), 0));
      } else if (rotation == ROTATION_270) {
        contentStream.setTextMatrix(
            Matrix.getRotateInstance(
                Math.toRadians(ROTATION_270), 0, page.getMediaBox().getUpperRightY()));
      }

      contentStream.newLineAtOffset(xOffset, yOffset);
      contentStream.showText(text);
      contentStream.endText();
    }
  }

  private String formatPageNumberText(int currentPage, int totalPages) {
    String format =
        (options.getPageFormat() != null && !options.getPageFormat().trim().isEmpty())
            ? options.getPageFormat()
            : "{0}";
    try {
      return MessageFormat.format(format, currentPage, totalPages);
    } catch (IllegalArgumentException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn(
            "Invalid MessageFormat for page numbers: '{}'. Defaulting to page number only.",
            options.getPageFormat(),
            e);
      }
      return String.valueOf(currentPage);
    }
  }
}
