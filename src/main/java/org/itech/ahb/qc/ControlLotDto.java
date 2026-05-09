package org.itech.ahb.qc;

/**
 * Active QC control lot identity, pulled from OE at registration time.
 *
 * <p>The bridge carries this so result parsers can attach a {@code lotNumber}
 * to QC samples whose inbound message embeds the lot identifier — FILE
 * sample-names containing the lot string, ASTM Q-segment field 3. Without
 * this list the bridge has no source of truth to disambiguate against;
 * matching is deferred to OE which can fall back to controlLevel match or
 * single-active-lot resolution.
 *
 * @param lotNumber    canonical lot string as registered in OE
 *                     (e.g. "LOT-LPC-26B")
 * @param controlLevel optional level label aligned with the QC profile
 *                     (e.g. "LPC", "HPC", "NORMAL"); null when the lot
 *                     was registered without a level
 * @param testId       OE test id this lot is bound to; informational —
 *                     parsers don't filter on it because the same sample
 *                     may produce results for multiple test codes
 */
public record ControlLotDto(
        String lotNumber,
        String controlLevel,
        Integer testId) {
}
