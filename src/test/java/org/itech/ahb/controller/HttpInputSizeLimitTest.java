package org.itech.ahb.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** HTTP input accepts analyzer traffic; a body larger than any result message is refused. */
class HttpInputSizeLimitTest {

  private final HttpInputSizeLimit limit = new HttpInputSizeLimit(1024);

  @Test
  void aDeclaredBodyOverTheLimitIsRefusedBeforeItIsRead() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/input");
    request.setContent(new byte[4096]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean reached = new AtomicBoolean();

    limit.doFilter(request, response, (req, res) -> reached.set(true));

    assertEquals(413, response.getStatus());
    assertFalse(reached.get());
  }

  @Test
  void aBodyWithoutADeclaredLengthStopsBeingReadAtTheLimit() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/input") {
      @Override
      public long getContentLengthLong() {
        return -1;
      }

      @Override
      public int getContentLength() {
        return -1;
      }
    };
    request.setContent(new byte[4096]);
    AtomicReference<ServletRequest> passed = new AtomicReference<>();

    limit.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set(req));

    IOException refused = assertThrows(IOException.class, () -> passed.get().getInputStream().readAllBytes());
    assertTrue(refused.getMessage().contains("1024"), refused.getMessage());
  }

  @Test
  void aBodyWithinTheLimitPassesUnchanged() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/input");
    request.setContent("H|\\^&\rL|1\r".getBytes());
    AtomicReference<byte[]> read = new AtomicReference<>();

    limit.doFilter(
      request,
      new MockHttpServletResponse(),
      (req, res) -> read.set(req.getInputStream().readAllBytes())
    );

    assertEquals("H|\\^&\rL|1\r", new String(read.get()));
  }
}
