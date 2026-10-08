package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** A small spreadsheet can declare enormous content; it is refused before it is held in memory. */
class FileResultParserInflateLimitTest {

  private static byte[] odsWithContentOf(long whitespaceBytes) throws IOException {
    ByteArrayOutputStream zip = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(zip)) {
      out.putNextEntry(new ZipEntry("content.xml"));
      out.write(
        ("<?xml version=\"1.0\"?><office:document-content " +
          "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\">").getBytes(StandardCharsets.UTF_8)
      );
      byte[] spaces = new byte[64 * 1024];
      java.util.Arrays.fill(spaces, (byte) ' ');
      for (long written = 0; written < whitespaceBytes; written += spaces.length) out.write(spaces);
      out.write("</office:document-content>".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return zip.toByteArray();
  }

  @Test
  void contentThatInflatesPastTheLimitIsRefused() throws IOException {
    byte[] bomb = odsWithContentOf(64L * 1024 * 1024);
    assertTrue(bomb.length < 1024 * 1024, "the archive itself is small: " + bomb.length);

    IOException refused = assertThrows(
      IOException.class,
      () -> FileResultParser.readOdsContentXml(new ByteArrayInputStream(bomb))
    );

    assertTrue(refused.getMessage().contains("exceeds"), refused.getMessage());
  }
}
