# RADIONE RP033 export: reconstruction

`RadioOne data Export_676_RP033_2026-10-10-00-22-19_2_0027.zip` is **not** the export captured at
the DRC lab (OGC-1610). `RadioneExports` in the test sources rebuilds it with the captured file's
layout: sample 676, kit RP033, instrument KM010-BE0027, Ebola and Marburg not detected with both
internal controls detected (Ct 25.61 and 25.25), wells C and D blank. The Result sheet lists the
same rows as the captured one. Its curve images are blank placeholders, its run record carries only
the fields the Bridge reads, and its other workbook sheets are empty.

The captured export replays green through `RadioneRp033BaselineProfileTest`. To replay it again, run

```
mvn test -Dtest=RadioneRp033BaselineProfileTest -Dradione.export=/path/to/export.zip
```

The spec asks the lab to confirm before the captured file is published as a fixture.
