package org.itech.ahb.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for FHIR R4 result routing.
 *
 * <p>When enabled, the bridge parses all incoming analyzer messages (HL7, ASTM,
 * FILE) into FHIR R4 transaction Bundles and routes them to OE's
 * {@code POST /analyzer/fhir} endpoint instead of the protocol-specific
 * endpoints ({@code /analyzer/hl7}, {@code /analyzer/astm}, etc.).
 */
@Configuration
@ConfigurationProperties(prefix = "bridge.routing")
@Getter
@Setter
public class FhirRoutingConfig {

    /**
     * Enable FHIR R4 result routing. When true (default), bridge parses all
     * protocols and sends FHIR Bundles. Set to false to use legacy raw routing
     * (e.g., during migration or for debugging).
     */
    private boolean useFhir = true;
}
