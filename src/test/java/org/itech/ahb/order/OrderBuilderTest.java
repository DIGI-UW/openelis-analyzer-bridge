package org.itech.ahb.order;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M4: the bridge builds the analyzer-protocol order from already-translated
 * analyzer codes (OE2 sent LOINC; the controller translated LOINC→code via the
 * registry). These pin the wire formats the mock analyzer parses:
 *   ASTM O-record: O|seq|ACCESSION||^^^CODE|R   (mock reads parts[2], parts[4])
 *   HL7  ORM:      one ORC/OBR per test, OBR-3=ACCESSION, OBR-4=^^^CODE
 */
@DisplayName("OrderBuilder — ASTM + HL7 ORM from analyzer codes")
class OrderBuilderTest {

    @Test
    @DisplayName("ASTM: H|P|O*|L with accession in O.3 and ^^^code in O.5")
    void astmOrder() {
        String msg = OrderBuilder.buildAstm("ACC-1", "PAT-9", List.of("MTB-RIF", "HIV-VL"));
        String[] recs = msg.split("\r");
        assertEquals("H|\\^&|||OpenELIS^Order^1.0|||||||LIS2-A2", recs[0]);
        assertEquals("P|1|||PAT-9", recs[1]);
        assertEquals("O|1|ACC-1||^^^MTB-RIF|R", recs[2]);
        assertEquals("O|2|ACC-1||^^^HIV-VL|R", recs[3]);
        assertEquals("L|1|N", recs[4]);
        // field layout the mock parses
        String[] o = recs[2].split("\\|", -1);
        assertEquals("ACC-1", o[2]);
        assertEquals("^^^MTB-RIF", o[4]);
    }

    @Test
    @DisplayName("HL7 ORM^O01: one ORC/OBR per ordered test, OBR-3=accession, OBR-4=^^^code")
    void hl7Order() {
        String msg = OrderBuilder.buildHl7Orm("ACC-7", "PAT-3", List.of("WBC", "HGB"));
        assertTrue(msg.contains("ORM^O01"), "must be an ORM^O01");
        List<String> obr = java.util.Arrays.stream(msg.split("\r")).filter(s -> s.startsWith("OBR|")).toList();
        assertEquals(2, obr.size(), "one OBR per ordered test");
        assertTrue(obr.get(0).contains("|ACC-7|ACC-7|^^^WBC"), "OBR-2/3=accession, OBR-4=^^^WBC; got: " + obr.get(0));
        assertTrue(obr.get(1).contains("^^^HGB"), "second OBR carries HGB");
        // correlation: accession present as filler so the mock echoes it
        assertTrue(msg.contains("ACC-7"));
    }

    @Test
    @DisplayName("ASTM: null patient → empty P field, no NPE")
    void astmNullPatient() {
        String msg = OrderBuilder.buildAstm("ACC-1", null, List.of("X"));
        assertTrue(msg.contains("\rP|1|||\r"));
    }

    @Test
    @DisplayName("empty codes → IllegalArgumentException (nothing to order)")
    void emptyCodes() {
        assertThrows(IllegalArgumentException.class,
                () -> OrderBuilder.buildAstm("ACC-1", "P", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> OrderBuilder.buildHl7Orm("ACC-1", "P", List.of()));
    }
}
