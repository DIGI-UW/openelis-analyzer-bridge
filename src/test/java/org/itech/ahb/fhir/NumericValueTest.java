package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NumericValueTest {

  @ParameterizedTest
  @ValueSource(strings = { "0", "7.5", "-3", "+4.0", ".5", "5.", "1520.5", "1.2E3", "4e-2", "123456789012345678" })
  void plainDecimalsAreNumeric(String value) {
    assertTrue(NumericValue.isBoundedDecimal(value), value);
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "",
      " ",
      "<5",
      ">100",
      "≤5",
      "1e2147483627",
      "1e1000",
      "NaN",
      "Infinity",
      "0x1F",
      "1,5",
      "7.5 ",
      "1234567890123456789012345678901234567890",
    }
  )
  void anythingElseIsNot(String value) {
    assertFalse(NumericValue.isBoundedDecimal(value), value);
  }
}
