package org.itech.ahb.normalizer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * An instrument's host query (a message with a Q record and no results). The Bridge holds no
 * orders, so it answers every query with "no information" (LIS2-A2 termination code {@code I});
 * the instrument would otherwise wait 60 seconds and cancel. A query whose Q.13 is {@code A}
 * is the instrument cancelling its last request and gets no answer.
 */
public final class AstmHostQuery {

  private static final DateTimeFormatter ASTM_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private AstmHostQuery() {}

  /** The reply to {@code query}, addressed back to its sender in its own delimiters; empty for a cancellation. */
  public static Optional<String> noOrdersReply(String query, LocalDateTime now) {
    String header = record(query, 'H');
    if (header == null || header.length() < 2) {
      return Optional.empty();
    }
    char field = header.charAt(1);
    String[] fields = header.split(java.util.regex.Pattern.quote(String.valueOf(field)), -1);
    String delimiters = at(fields, 1);
    if (cancels(query, field, delimiters)) {
      return Optional.empty();
    }
    String[] reply = new String[14];
    java.util.Arrays.fill(reply, "");
    reply[0] = "H";
    reply[1] = delimiters;
    reply[2] = UUID.randomUUID().toString().replace("-", "");
    reply[4] = at(fields, 9).isBlank() ? "LIS" : at(fields, 9);
    reply[9] = at(fields, 4);
    reply[11] = at(fields, 11).isBlank() ? "P" : at(fields, 11);
    reply[12] = at(fields, 12).isBlank() ? "1394-97" : at(fields, 12);
    reply[13] = now.format(ASTM_TIMESTAMP);
    String separator = String.valueOf(field);
    return Optional.of(String.join(separator, reply) + "\r" + String.join(separator, "L", "1", "I"));
  }

  private static boolean cancels(String query, char field, String delimiters) {
    String request = record(query, 'Q');
    if (request == null) {
      return false;
    }
    String status = at(request.split(java.util.regex.Pattern.quote(String.valueOf(field)), -1), 12);
    String repeat = delimiters.isEmpty() ? "\\" : java.util.regex.Pattern.quote(delimiters.substring(0, 1));
    for (String code : status.split(repeat)) {
      if ("A".equals(code.trim())) {
        return true;
      }
    }
    return false;
  }

  private static String record(String message, char type) {
    if (message == null) {
      return null;
    }
    for (String line : message.split("\r\n|\r|\n")) {
      String trimmed = line.trim();
      if (trimmed.length() > 1 && trimmed.charAt(0) == type && !Character.isLetterOrDigit(trimmed.charAt(1))) {
        return trimmed;
      }
    }
    return null;
  }

  private static String at(String[] fields, int index) {
    return index < fields.length ? fields[index] : "";
  }
}
