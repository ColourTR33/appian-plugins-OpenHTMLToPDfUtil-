import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * A utility class for common file and I/O operations.
 */

public final class FileUtils {

  private static final int BUFFER_SIZE = 8192; // 8 KB buffer

  // Private constructor to prevent instantiation
  private FileUtils() {
  }

  /**
   * Writes an InputStream to a specified File.
   *
   * @param inputStream
   *          The source stream to read from.
   * @param outputFile
   *          The destination file to write to. The file will be created if it does not exist.
   * @throws IOException
   *           if an I/O error occurs.
   */
  public static void copyInputStreamToFile(InputStream inputStream, File outputFile) throws IOException {
    Objects.requireNonNull(inputStream, "InputStream cannot be null");
    Objects.requireNonNull(outputFile, "Output file cannot be null");

    try (OutputStream outputStream = new FileOutputStream(outputFile)) {
      byte[] buffer = new byte[BUFFER_SIZE];
      int bytesRead;
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, bytesRead);
      }
    }
  }
}
