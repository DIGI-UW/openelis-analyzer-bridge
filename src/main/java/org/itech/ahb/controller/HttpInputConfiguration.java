package org.itech.ahb.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpInputConfiguration {

  /** HTTP input carries result messages; 20 MB matches the watched-file limit. */
  @Bean
  public FilterRegistrationBean<HttpInputSizeLimit> httpInputSizeLimit(
    @Value("${bridge.http.max-input-bytes:20971520}") long maxInputBytes
  ) {
    FilterRegistrationBean<HttpInputSizeLimit> registration = new FilterRegistrationBean<>(
      new HttpInputSizeLimit(maxInputBytes)
    );
    registration.addUrlPatterns("/input");
    return registration;
  }

  /** Pairing is open to anonymous callers and its request is a short code. */
  @Bean
  public FilterRegistrationBean<HttpInputSizeLimit> pairingSizeLimit() {
    FilterRegistrationBean<HttpInputSizeLimit> registration = new FilterRegistrationBean<>(
      new HttpInputSizeLimit(16 * 1024)
    );
    registration.addUrlPatterns("/pairing");
    return registration;
  }
}
