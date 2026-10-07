# Cepheid example messages

ASTM example messages copied from Cepheid's LIS guidance, one message per file, wrapped
lines joined. The tests replay each one against the shipped GeneXpert profile.

- `hivvl-*.astm`: Cepheid 303-0251 Rev. A, section 2.1.1 (Xpert HIV-1 Viral Load XC).
- `302-7279-<section>.astm`: Cepheid 302-7279 Rev. A, the section number is the example's
  (Xpert Xpress CoV-2/Flu/RSV plus):

| File | Panel | Example |
| ---- | ----- | ------- |
| `302-7279-6.1.1.1.astm` | COVFLURSVPLUS | SARS-CoV-2: Negative, FLU A: Negative, FLU B: Negative, RSV: Negative |
| `302-7279-6.1.1.2.astm` | COVFLURSVPLUS | SARS-CoV-2: Positive, FLU A: Negative, FLU B: Negative, RSV: Negative |
| `302-7279-6.1.1.3.astm` | COVFLURSVPLUS | SARS-CoV-2: Negative, FLU A: Positive, FLU B: Negative, RSV: Negative |
| `302-7279-6.1.1.4.astm` | COVFLURSVPLUS | SARS-CoV-2: Negative, FLU A: Negative, FLU B: Positive, RSV: Negative |
| `302-7279-6.1.1.5.astm` | COVFLURSVPLUS | SARS-CoV-2: Negative, FLU A: Negative, FLU B: Negative, RSV: Positive |
| `302-7279-6.1.1.6.astm` | COVFLURSVPLUS | SARS-CoV-2: Positive, FLU A: Positive, FLU B: Positive, RSV: Positive |
| `302-7279-6.1.1.7.astm` | COVFLURSVPLUS | SARS-CoV-2: Error |
| `302-7279-6.1.1.8.astm` | COVFLURSVPLUS | SARS-CoV-2: Invalid |
| `302-7279-6.1.1.9.astm` | COVFLURSVPLUS | SARS-CoV-2: No Result |
| `302-7279-6.2.1.1.astm` | COVFLUPLUS | SARS-CoV-2: Negative, FLU A: Negative, FLU B: Negative, RSV: N.A. |
| `302-7279-6.2.1.2.astm` | COVFLUPLUS | SARS-CoV-2: Positive, FLU A: Negative, FLU B: Negative, RSV: N.A. |
| `302-7279-6.2.1.3.astm` | COVFLUPLUS | SARS-CoV-2: Negative, FLU A: Positive, FLU B: Negative, RSV: N.A. |
| `302-7279-6.2.1.4.astm` | COVFLUPLUS | SARS-CoV-2: Negative, FLU A: Negative, FLU B: Positive, RSV: N.A. |
| `302-7279-6.2.1.5.astm` | COVFLUPLUS | SARS-CoV-2: Positive, FLU A: Positive, FLU B: Positive, RSV: N.A. |
| `302-7279-6.2.1.6.astm` | COVFLUPLUS | SARS-CoV-2: Error |
| `302-7279-6.2.1.7.astm` | COVFLUPLUS | SARS-CoV-2: Invalid |
| `302-7279-6.3.1.1.astm` | COVPLUS | SARS-CoV-2: Negative, FLU A: N.A, FLU B: N.A, RSV: N.A. |
| `302-7279-6.3.1.2.astm` | COVPLUS | SARS-CoV-2: Positive, FLU A: N.A, FLU B: N.A, RSV: N.A. |
| `302-7279-6.3.1.3.astm` | COVFLUPLUS | SARS-CoV-2: Error |
| `302-7279-6.3.1.4.astm` | COVFLUPLUS | SARS-CoV-2: Invalid |
