package org.itech.ahb.fhir;

/**
 * The patient an instrument reported on its own records, kept as reported. It is evidence for the
 * reviewer, never a patient record.
 */
public record InstrumentPatient(String identifier, String family, String given) {}
