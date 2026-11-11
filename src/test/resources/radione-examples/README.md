# RADIONE RP033 export: reconstruction

`RadioOne data Export_676_RP033_2026-10-10-00-22-19_2_0027.zip` is **not** the export captured at
the DRC lab (OGC-1610). It is rebuilt from the RADIONE RP033 integration spec v0.4, sections 2.1,
4.1 to 4.3 and 10, by `RadioneExports` in the test sources: sample 676, kit RP033, instrument
KM010-BE0027, Ebola and Marburg not detected with both internal controls detected (Ct 25.61 and
25.25), wells C and D blank. Its curve images are blank placeholders and its other workbook sheets
are empty.

To replay the captured export instead, put it here under the same name, or run

```
mvn test -Dtest=RadioneRp033BaselineProfileTest -Dradione.export=/path/to/export.zip
```

The spec asks the lab to confirm before the captured file is published as a fixture.
