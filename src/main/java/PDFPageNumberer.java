import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.util.Matrix;

/**
 * Adds page numbers to an existing PDF document based on a provided configuration.
 */
public class PDFPageNumberer {

  private static final Logger LOG = LogManager.getLogger(PDFPageNumberer.class);
  private final PageNumberingOptions options;

  /**
   * Constructs a PdfPageNumberer with a specific set of options.
   * * @param options
   * The configuration for font, size, and format.
   */
  public PDFPageNumberer(PageNumberingOptions options) {
    this.options = Objects.requireNonNull(options, "PageNumberingOptions cannot be null");
  }

  /**
   * Adds page numbers to the given PDF file.
   * The format and style are determined by the options provided at construction time.
   *
   * @param pdfFile
   *          The PDF file to modify.
   * @param startPage
   *          The page number to start adding numbers from (1-based).
   * @param xOffset
   *          The horizontal position from the bottom-left corner.
   * @param yOffset
   *          The vertical position from the bottom-left corner.
   * @throws IOException
   *           if the file cannot be read or written.
   * @throws IllegalArgumentException
   *           if the startPage is invalid.
   */
  public void addPageNumbers(File pdfFile, int startPage, float xOffset, float yOffset)
    throws IOException, IllegalArgumentException {

    Objects.requireNonNull(pdfFile, "PDF file cannot be null");
    LOG.info("Starting to add page numbers to '{}' with options: [Font={}, Size={}, Format='{}']",
      pdfFile.getName(), options.getFont().getName(), options.getFontSize(), options.getPageFormat());

    // ** FIX APPLIED HERE: Updated to use Loader.loadPDF() for PDFBox 3.x compatibility **
    try (PDDocument doc = Loader.loadPDF(pdfFile)) {
      if (startPage > doc.getNumberOfPages() || startPage < 1) {
        throw new IllegalArgumentException(
          String.format("Invalid start page: %d. Document only has %d pages.", startPage, doc.getNumberOfPages()));
      }

      for (int i = startPage - 1; i < doc.getNumberOfPages(); i++) {
        PDPage page = doc.getPage(i);
        int currentPageNumber = i + 1;
        String text = formatPageNumberText(currentPageNumber, doc.getNumberOfPages());

        addTextToPage(doc, page, text, xOffset, yOffset);
      }

      doc.save(pdfFile);
      LOG.info("Successfully added page numbers and saved the document.");
    }
  }

  private void addTextToPage(PDDocument doc, PDPage page, String text, float xOffset, float yOffset) throws IOException {
    try (PDPageContentStream contentStream = new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {
      contentStream.beginText();
      // Use the font and size from the injected options object
      contentStream.setFont(options.getFont(), options.getFontSize());

      // Handle page rotation to ensure text is upright
      int rotation = page.getRotation();
      if (rotation == 90) {
        contentStream.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(90), page.getMediaBox().getUpperRightX(), 0));
      } else if (rotation == 270) {
        contentStream.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(270), 0, page.getMediaBox().getUpperRightY()));
      }

      contentStream.newLineAtOffset(xOffset, yOffset);
      contentStream.showText(text);
      contentStream.endText();
    }
  }

  private String formatPageNumberText(int currentPage, int totalPages) {
    String format = (options.getPageFormat() != null && !options.getPageFormat().trim().isEmpty()) ? options.getPageFormat() : "{0}";
    try {
      return MessageFormat.format(format, currentPage, totalPages);
    } catch (IllegalArgumentException e) {
      LOG.warn("Invalid MessageFormat for page numbers: '{}'. Defaulting to page number only.", options.getPageFormat(), e);
      return String.valueOf(currentPage);
    }
  }
}
