package org.itech.ahb.mllp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An accepted MLLP connection that counts toward its listener's connection limit until closed, and
 * whose input fails once a message outgrows its byte limit or its deadline.
 */
final class LimitedMllpSocket extends Socket {

  private static final int START_BLOCK = 0x0B;
  private static final int END_BLOCK = 0x1C;

  private final MllpLimits limits;
  private final AtomicInteger openConnections;
  private final AtomicBoolean counted = new AtomicBoolean();
  private InputStream input;

  LimitedMllpSocket(MllpLimits limits, AtomicInteger openConnections) {
    this.limits = limits;
    this.openConnections = openConnections;
  }

  /** Counts this connection; false, and nothing counted, when the listener is already full. */
  boolean admit() {
    if (openConnections.incrementAndGet() > limits.maxConnections()) {
      openConnections.decrementAndGet();
      return false;
    }
    counted.set(true);
    return true;
  }

  @Override
  public synchronized InputStream getInputStream() throws IOException {
    if (input == null) input = new MessageLimit(super.getInputStream());
    return input;
  }

  @Override
  public synchronized void close() throws IOException {
    try {
      super.close();
    } finally {
      if (counted.compareAndSet(true, false)) openConnections.decrementAndGet();
    }
  }

  private final class MessageLimit extends FilterInputStream {

    private long bytes;
    private long startedAt = -1;

    MessageLimit(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      checkDeadline();
      int b;
      try {
        b = super.read();
      } catch (SocketTimeoutException idle) {
        checkDeadline();
        throw idle;
      }
      if (b >= 0) observe(b);
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      checkDeadline();
      int n;
      try {
        n = super.read(buffer, offset, length);
      } catch (SocketTimeoutException idle) {
        checkDeadline();
        throw idle;
      }
      for (int i = 0; i < n; i++) observe(buffer[offset + i] & 0xFF);
      return n;
    }

    private void observe(int b) throws IOException {
      if (b == END_BLOCK) {
        bytes = 0;
        startedAt = -1;
        return;
      }
      if (b == START_BLOCK && startedAt < 0) startedAt = System.nanoTime();
      if (++bytes > limits.maxMessageBytes()) {
        throw new IOException("MLLP message exceeds " + limits.maxMessageBytes() + " bytes");
      }
      checkDeadline();
    }

    private void checkDeadline() throws IOException {
      if (startedAt >= 0 && System.nanoTime() - startedAt > limits.messageTimeout().toNanos()) {
        throw new IOException("MLLP message not complete within " + limits.messageTimeout().toSeconds() + "s");
      }
    }
  }
}
