# fixtures — committed real-producer JT files

Unlike `fixtures-local/` (IP-encumbered files, gitignored), everything here is redistributable
with documented provenance and is part of the permanent acceptance spine (issue #1 and its
fixture-policy amendment).

## nist-mtc-crada-assembly.jt

- **What**: the NIST MTC "Box Assembly" — a multi-part CRADA assembly with precise geometry
  (XT B-rep), PMI, metadata, wireframe and three LOD tiers per shape; 107 segments.
- **Producer**: Siemens NX export; header reads `Version 10.5 JT  DM 9.8.0.0`
  (the Siemens JT writer / DirectModel toolkit).
- **Source**: `NIST-MTC-Assembly/NX/NIST mtc crada assembly.jt` inside
  <https://www.nist.gov/system/files/documents/noindex/2025/09/04/NIST-MTC-Assembly.zip>
  (landing page: <https://www.nist.gov/document/nist-cad-models-mtc-assembly>), file dated
  2021-06-25, renamed here without content change.
  MD5 `8debba95f2ba87cbe46c78b77b31c007`.
- **Terms** (NIST disclaimer on the download page, quoted):
  > "The test cases, CAD models, and STEP files can be used without any restrictions. Their
  > use in other software or hardware products does not imply a recommendation or endorsement
  > of those products by NIST. We would appreciate acknowledgement if any of the test cases,
  > CAD models, STEP files, or screenshots of the models are used, however, the use of the
  > NIST logo seen at the top of this page is not allowed in promotional materials."

  As a work of the US federal government it carries no US copyright (17 U.S.C. §105; see also
  <https://www.nist.gov/oism/copyrights>).
- **Acknowledgement**: this fixture comes from the NIST project *"Design, Manufacturing, and
  Inspection Data for a Box Assembly"* (MTC CRADA). Thanks to NIST for publishing it without
  restrictions. No endorsement by NIST is implied; the NIST logo is not used.
