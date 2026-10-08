package org.itech.ahb.fhir;

import java.util.regex.Pattern;

/**
 * Whether an analyzer value is sent to OpenELIS as a number. Only a plain decimal of bounded size
 * qualifies: a comparison ({@code <5}), a special value or an enormous exponent is reported as text,
 * so no conversion downstream can be made to expand it.
 */
final class NumericValue {

  private static final int MAX_LENGTH = 48;
  private static final Pattern BOUNDED_DECIMAL = Pattern.compile(
    "[+-]?(?:\\d{1,18}(?:\\.\\d{0,18})?|\\.\\d{1,18})(?:[eE][+-]?\\d{1,3})?"
  );

  private NumericValue() {}

  static boolean isBoundedDecimal(String value) {
    return value != null && value.length() <= MAX_LENGTH && BOUNDED_DECIMAL.matcher(value).matches();
  }
}
