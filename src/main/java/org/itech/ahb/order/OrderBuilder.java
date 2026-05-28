package org.itech.ahb.order;

import java.util.List;

/**
 * Builds analyzer-protocol order messages (ASTM LIS2-A2 records, HL7 v2 ORM^O01)
 * from analyzer test codes. This is the order-construction that used to live in
 * OE2 — moved into the bridge so OE2 stays analyzer-agnostic (OE2 sends LOINC;
 * {@code OutboundOrderController} translates LOINC→code via the registry, then
 * this builds the wire message the analyzer speaks).
 *
 * <p>Codes passed here are already the analyzer's own test codes. The accession
 * is placed where the analyzer (and our mock) echoes it back on the result for
 * correlation: ASTM O-record field 3, HL7 OBR-3 (filler order number).
 */
public final class OrderBuilder {

    private static final String CR = "\r";

    private OrderBuilder() {
    }

    /** ASTM H|P|O…|L. One O-record per code: {@code O|seq|accession||^^^code|R}. */
    public static String buildAstm(String accession, String patientId, List<String> analyzerCodes) {
        requireCodes(analyzerCodes);
        String pid = patientId == null ? "" : patientId;
        StringBuilder sb = new StringBuilder();
        sb.append("H|\\^&|||OpenELIS^Order^1.0|||||||LIS2-A2").append(CR);
        sb.append("P|1|||").append(pid).append(CR);
        int seq = 1;
        for (String code : analyzerCodes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            sb.append("O|").append(seq).append("|").append(accession)
                    .append("||^^^").append(code).append("|R").append(CR);
            seq++;
        }
        sb.append("L|1|N").append(CR);
        return sb.toString();
    }

    /**
     * HL7 ORM^O01: MSH + PID + one ORC/OBR group per ordered test. OBR-2/3 carry
     * the accession (filler order number) so the analyzer echoes it on the
     * result; OBR-4 is the universal service identifier {@code ^^^code^code}.
     */
    public static String buildHl7Orm(String accession, String patientId, List<String> analyzerCodes) {
        requireCodes(analyzerCodes);
        String pid = patientId == null ? "" : patientId;
        StringBuilder sb = new StringBuilder();
        sb.append("MSH|^~\\&|OE2|LAB|ANALYZER|LAB|").append(timestamp())
                .append("||ORM^O01|OE").append(timestamp()).append("|P|2.3.1").append(CR);
        sb.append("PID|1||").append(pid).append("^^^HOSP").append(CR);
        int seq = 1;
        for (String code : analyzerCodes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            sb.append("ORC|NW|").append(accession).append("|").append(accession).append(CR);
            sb.append("OBR|").append(seq).append("|").append(accession).append("|").append(accession)
                    .append("|^^^").append(code).append("^").append(code).append(CR);
            seq++;
        }
        return sb.toString();
    }

    private static void requireCodes(List<String> codes) {
        if (codes == null || codes.stream().noneMatch(c -> c != null && !c.isBlank())) {
            throw new IllegalArgumentException("at least one analyzer code is required");
        }
    }

    private static String timestamp() {
        return new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date());
    }
}
