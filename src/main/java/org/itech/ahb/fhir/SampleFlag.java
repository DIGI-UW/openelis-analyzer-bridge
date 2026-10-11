package org.itech.ahb.fhir;

/**
 * A warning an instrument raised about a sample, such as a hematology analyzer's Anemia alarm: its
 * code, the instrument's name for it and the value that raised it, as sent. It travels with the
 * sample's results and is never a result itself.
 */
public record SampleFlag(String code, String name, String value) {}
