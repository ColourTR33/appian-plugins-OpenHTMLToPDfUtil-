
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;

/**
 * A configuration object that holds styling and formatting options for page numbering.
 * Use the nested Builder class to construct an instance.
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

  // Public getters for the options
  public PDFont getFont() {
    return font;
  }

  public float getFontSize() {
    return fontSize;
  }

  public String getPageFormat() {
    return pageFormat;
  }

  /**
   * @return A new instance of the Builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The Builder class for creating PageNumberingOptions instances.
   * This is where the default values now live.
   */
  public static class Builder {
    private PDFont font = new PDType1Font(FontName.HELVETICA);// ** FIX APPLIED HERE: Updated the font initialization to use the modern
                                                              // PDFBox 3.x API **
    private float fontSize = 10.0f;
    private String pageFormat = "Page {0} of {1}";

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
