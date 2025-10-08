import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * A configuration object that holds styling and formatting options for page numbering. Use the
 * nested Builder class to construct an instance.
 */
public final class PageNumberingOptions {

  private final PDFont font;
  private final float fontSize;
  private final String pageFormat;

  // Private constructor to force use of the Builder
  private PageNumberingOptions(Builder builder) {
    this.font = builder.font;
    this.fontSize = builder.fontSize;
    this.pageFormat = builder.pageFormat;
  }

  /**
   * @return A new instance of the Builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Returning a direct reference is intentional for interoperability with other PDFBox APIs. The risk is documented.")
  public PDFont getFont() {
    return this.font;
  }

  public float getFontSize() {
    return fontSize;
  }

  public String getPageFormat() {
    return pageFormat;
  }

  /** The Builder class for creating PageNumberingOptions instances. */
  public static class Builder {

    private transient PDFont font = PDType1Font.HELVETICA; // NOPMD
    private transient float fontSize = 10.0f; // NOPMD
    private transient String pageFormat = "Page {0} of {1}"; // NOPMD

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "Storing a direct reference is intentional. PDFont objects are not easily cloneable.")
    public Builder font(PDFont font) {
      this.font = font;
      return this;
    }

    public Builder fontSize(float fontSize) {
      this.fontSize = fontSize;
      return this;
    }

    public Builder pageFormat(String pageFormat) {
      this.pageFormat = pageFormat;
      return this;
    }

    public PageNumberingOptions build() {
      return new PageNumberingOptions(this);
    }
  }
}
