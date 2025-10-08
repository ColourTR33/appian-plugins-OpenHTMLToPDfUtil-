import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

public final class FileUtils {

  private FileUtils() {
    // Utility class
  }

  public static void copyInputStreamToFile(InputStream inputStream, File file) throws IOException {
    try (OutputStream outputStream = Files.newOutputStream(file.toPath())) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = inputStream.read(buffer)) != -1) { // NOPMD
        outputStream.write(buffer, 0, bytesRead);
      }
    }
  }
}
