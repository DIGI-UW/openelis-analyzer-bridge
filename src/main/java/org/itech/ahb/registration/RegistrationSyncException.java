package org.itech.ahb.registration;

public class RegistrationSyncException extends RuntimeException {

  public RegistrationSyncException(String message) {
    super(message);
  }

  public RegistrationSyncException(String message, Throwable cause) {
    super(message, cause);
  }
}
