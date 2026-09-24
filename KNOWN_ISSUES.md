# Known issues for the local source candidate

- `SmsInnerPagingRenderTest` previously failed on its initial latest-message assertion in a desktop test. The cause is unproven and the failure remains historical and unresolved; changing license materials does not fix it.
- The archived 2026-09-20 Android build belongs to an earlier snapshot. Each new source revision needs its own independent build receipt; a host build does not establish phone or carrier acceptance.
- The public country table is MCC-to-ISO data, not carrier policy. There is no public replacement for the private engine; a client-only APK is not a working VoWiFi deployment.
- Source and binary distribution compliance, including complete corresponding source and dependencies, remains to be assessed. IPC separation alone is not an exemption.
