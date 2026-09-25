# HL7 specimen position

The optional immutable profile field
`configDefaults.extractionOverrides.specimenPosition` defines which specimen
segment supplies observation recognition evidence. It is not a connection override.

- `PRECEDING` (also the behavior when omitted): recognize each OBX immediately
  using fields already received. Existing published profiles retain this behavior.
- `FOLLOWING_OBX`: capture the observation, accession and existing non-SPM
  evidence at OBX. Read its following SPM (with optional intervening NTE). Finalize
  before the next OBX, ORC, OBR, PID or MSH, or at message end. A new OBX clears
  prior SPM fields, including when the new observation cannot be parsed. A
  missing or shorter SPM cannot inherit the previous observation's role.

Unknown or non-string values fail profile validation. Publication includes the
setting in the immutable revision fingerprint. A newly published revision never
changes a configured connection's existing pin. Recognition remains entirely
controlled by that revision's explicit rules; this setting supplies field scope,
not a control marker or a fallback classifier.

Use FOLLOWING_OBX only when the instrument's documented export places SPM after
its observation. Automatic layout inference is ambiguous and is not performed.
The existing parser still returns one accession per message; this addition does
not add multi-patient message support.

Regression coverage includes per-observation Q/P/missing evidence, alternative
separators, order boundaries, unchanged preceding behavior, and saved HTTP/MLLP
connections across restart and publication of a newer unselected revision.
These are synthetic protocol tests, not physical-instrument acceptance.
