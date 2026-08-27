package org.itech.ahb.lib.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Charset handling for the two distinct layers of an ASTM exchange: the transport (bytes on the
 * wire) and the payload (result text).
 *
 * <p>ASTM E1381 defines the frame checksum as an arithmetic sum over the <em>bytes</em> the sender
 * transmitted, so the transport layer must be byte-transparent. {@link #TRANSPORT} maps every byte
 * 1:1 onto U+0000-U+00FF, which means a frame read through it can always be re-encoded to the exact
 * bytes the sender summed, whatever encoding the payload happens to use. Reading the socket as
 * UTF-8 instead makes any non-ASCII byte undecodable: it becomes U+FFFD, whose UTF-8 re-encoding is
 * three bytes rather than one, and the checksum then cannot match.
 *
 * <p>Payload text is a separate question, and analyzers disagree on it. A Cepheid GeneXpert at a
 * French-language site emits Latin-1/CP1252 ({@code NON DÉTECTÉ}); other analyzers emit UTF-8.
 * {@link #decodePayload(String)} resolves this without configuration.
 */
public final class AstmCharsets {

  /**
   * Byte-transparent charset for the transport layer and checksum arithmetic.
   *
   * <p>ISO-8859-1 defines all 256 byte values and maps them 1:1 onto U+0000-U+00FF, so decoding and
   * re-encoding through it is lossless for any byte sequence.
   */
  public static final Charset TRANSPORT = StandardCharsets.ISO_8859_1;

  private AstmCharsets() {}

  /**
   * Interprets text that was read through {@link #TRANSPORT} as payload text.
   *
   * <p>The wire bytes are recovered and strictly decoded as UTF-8. If they are valid UTF-8 the
   * decoded form is returned, so analyzers that send UTF-8 are read correctly. If they are not, the
   * byte-transparent text is returned unchanged, which for the Latin-1 range is already the correct
   * reading — {@code 0xC9} is {@code É} in both ISO-8859-1 and CP1252.
   *
   * <p>Pure ASCII is valid UTF-8 and decodes to itself, so the overwhelmingly common case is
   * unaffected. A Latin-1 string whose bytes happen to form valid UTF-8 would be read as UTF-8;
   * that requires an accented character to be followed by a specific continuation byte, which does
   * not occur in ASTM result text in practice.
   *
   * @param transportText text read through {@link #TRANSPORT}, or null
   * @return the payload reading of that text, or null if {@code transportText} was null
   */
  public static String decodePayload(String transportText) {
    if (transportText == null || transportText.isEmpty()) {
      return transportText;
    }
    byte[] wireBytes = transportText.getBytes(TRANSPORT);
    CharsetDecoder strictUtf8 = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return strictUtf8.decode(ByteBuffer.wrap(wireBytes)).toString();
    } catch (CharacterCodingException e) {
      return transportText;
    }
  }

  /**
   * Reports whether the wire bytes behind transport-decoded text are valid UTF-8.
   *
   * <p>Lets a caller decide a payload encoding once for a whole message and apply it uniformly to
   * every record, rather than re-deciding per record.
   *
   * @param transportText text read through {@link #TRANSPORT}, or null
   * @return true if the underlying bytes are valid UTF-8
   */
  public static boolean isUtf8Payload(String transportText) {
    if (transportText == null || transportText.isEmpty()) {
      return true;
    }
    CharsetDecoder strictUtf8 = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      strictUtf8.decode(ByteBuffer.wrap(transportText.getBytes(TRANSPORT)));
      return true;
    } catch (CharacterCodingException e) {
      return false;
    }
  }
}
