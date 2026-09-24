# Public AOSP MCC country table provenance

This directory is the independent public-country input for
`tools/export/build_country_catalog.py`. It is not mixed OEM/operator
catalog data and is not a legal clearance.

## Source

| Field | Value |
|---|---|
| Project | Android Open Source Project `platform/frameworks/opt/telephony` |
| File | `src/java/com/android/internal/telephony/MccTable.java` |
| URL | https://android.googlesource.com/platform/frameworks/opt/telephony/+/307806916047d68377ff74674f95ca499243e068/src/java/com/android/internal/telephony/MccTable.java |
| Commit | `307806916047d68377ff74674f95ca499243e068` |
| License | Apache License 2.0 |
| Copyright | Copyright (C) 2006 The Android Open Source Project |
| Raw SHA-256 | `68a2ed822151f0cdacc023fb924c1dff9bc7e913bcad5220dd1f42503e715840` |
| Size | 29630 bytes, 585 LF lines |

Repository `LICENSE`/`NOTICE` at that telephony snapshot returned HTTP 404.
That is not treated as "header alone is enough": this directory includes
the full Apache-2.0 license text plus original copyright, this provenance
record, and a derived-data modification notice.

## Byte verification

The pinned googlesource file and two independent upstream mirrors matched
the raw SHA-256 above. `MccTable.java` here is an exact copy of those
29630 bytes.

## License file

`LICENSE` contains the standard Apache License 2.0 text, including the
terms and appendix. SHA-256:
`cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`.

## Borrowed content

Borrowed from the pinned Java file:

- anchored `sTable.add(new MccEntry(mcc, "iso", digits))` records
- MCC integers and lowercase ISO 3166-1 alpha-2 strings only

Not borrowed:

- AOSP Java helpers, locale/timezone/configuration logic
- MNC width as generated catalog data
- OEM arrays, operator profiles, ePDG, PANI, IMSI, or private carrier policy

## Generator output

`app/manager/shared/src/commonMain/kotlin/com/vowifi/sw/manager/model/CountryCatalogGenerated.kt`
is generated MCC-to-ISO2 data. The generated output SHA-256 is
`ad3e1abdfaa5f6783f8303520551240ccfa46d798a3844d3ca160b3a2a5386ca`.
Proposed client licensing does not relicense this Apache-2.0 source.
The table is not carrier policy. This record is not rights approval.
