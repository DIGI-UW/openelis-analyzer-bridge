package org.itech.ahb.controller;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Bounds the body of an HTTP input request. A declared length over the limit is refused with 413
 * before anything is read; a body without a declared length fails once it passes the limit.
 */
public class HttpInputSizeLimit implements Filter {

  private final long maxBytes;

  public HttpInputSizeLimit(long maxBytes) {
    if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
    this.maxBytes = maxBytes;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
    throws IOException, ServletException {
    if (request.getContentLengthLong() > maxBytes) {
      ((HttpServletResponse) response).sendError(
        HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
        "Request body exceeds " + maxBytes + " bytes"
      );
      return;
    }
    chain.doFilter(new Limited((HttpServletRequest) request), response);
  }

  private final class Limited extends HttpServletRequestWrapper {

    private ServletInputStream input;

    Limited(HttpServletRequest request) {
      super(request);
    }

    @Override
    public synchronized ServletInputStream getInputStream() throws IOException {
      if (input == null) input = new Counted(super.getInputStream());
      return input;
    }
  }

  private final class Counted extends ServletInputStream {

    private final ServletInputStream delegate;
    private long count;

    Counted(ServletInputStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public int read() throws IOException {
      int b = delegate.read();
      if (b >= 0) count(1);
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = delegate.read(buffer, offset, length);
      if (n > 0) count(n);
      return n;
    }

    private void count(int n) throws IOException {
      count += n;
      if (count > maxBytes) throw new IOException("Request body exceeds " + maxBytes + " bytes");
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      delegate.setReadListener(listener);
    }
  }
}
