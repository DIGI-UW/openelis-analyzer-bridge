package org.itech.ahb.connection;

public class AnalyzerConnectionException extends RuntimeException {

  public enum Kind {
    INVALID,
    NOT_FOUND,
    CONFLICT,
  }

  private final Kind kind;

  public AnalyzerConnectionException(String message) {
    this(Kind.INVALID, message);
  }

  public AnalyzerConnectionException(String message, Throwable cause) {
    this(Kind.INVALID, message, cause);
  }

  public AnalyzerConnectionException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public AnalyzerConnectionException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
