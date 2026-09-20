#!/usr/bin/env bash
set -euo pipefail

APK=${1:?usage: apk_manifest_test.sh APK MODULE_PROP MODULE_OUTPUT}
MODULE_PROP=${2:?module.prop required}
MODULE_ROOT=${3:?module output required}
APK_ANALYZER=${ANDROID_SDK_ROOT:?explicit Android SDK required}/cmdline-tools/latest/bin/apkanalyzer

test -x "$APK_ANALYZER" || { printf 'missing apkanalyzer: %s\n' "$APK_ANALYZER" >&2; exit 1; }
command -v xmllint >/dev/null 2>&1 || { printf 'missing xmllint\n' >&2; exit 1; }
test -f "$APK" || { printf 'missing APK: %s\n' "$APK" >&2; exit 1; }

package_name=$("$APK_ANALYZER" manifest print "$APK" | xmllint --xpath 'string(/*/@package)' -)
if [ "$package_name" != "com.vowifi.sw" ]; then
    printf 'APK package must be com.vowifi.sw\n' >&2
    exit 1
fi
privileged_count=$("$APK_ANALYZER" manifest print "$APK" | xmllint --xpath \
    'count(//*[local-name()="uses-permission" and @*[local-name()="name"]="android.permission.READ_PRIVILEGED_PHONE_STATE"])' -)
if [ "$privileged_count" != "1" ]; then
    printf 'APK must declare android.permission.READ_PRIVILEGED_PHONE_STATE exactly once\n' >&2
    exit 1
fi
allowlist="$MODULE_ROOT/system/system_ext/etc/permissions/privapp-permissions-com.vowifi.sw.xml"
test -f "$allowlist" || { printf 'missing same-partition allowlist\n' >&2; exit 1; }
grant_count=$(xmllint --xpath \
    'count(/permissions/privapp-permissions[@package="com.vowifi.sw"]/permission[@name="android.permission.READ_PRIVILEGED_PHONE_STATE"])' "$allowlist")
if [ "$grant_count" != "1" ]; then
    printf 'allowlist must grant READ_PRIVILEGED_PHONE_STATE to com.vowifi.sw exactly once\n' >&2
    exit 1
fi
packaged_apk="$MODULE_ROOT/system/system_ext/priv-app/SoftVoWiFi/SoftVoWiFi.apk"
test -f "$packaged_apk" || { printf 'missing same-partition priv-app APK\n' >&2; exit 1; }
cmp -s "$APK" "$packaged_apk" || { printf 'packaged APK differs from checked APK\n' >&2; exit 1; }

application_name=$(
    "$APK_ANALYZER" manifest print "$APK" \
        | xmllint --xpath \
            'string(//*[local-name()="application"]/@*[local-name()="name"])' -
)
exported=$(
    "$APK_ANALYZER" manifest print "$APK" \
        | xmllint --xpath \
            'string(//*[local-name()="service" and @*[local-name()="name"]="com.vowifi.sw.ObserverService"]/@*[local-name()="exported"])' -
)
permission=$(
    "$APK_ANALYZER" manifest print "$APK" \
        | xmllint --xpath \
            'string(//*[local-name()="service" and @*[local-name()="name"]="com.vowifi.sw.ObserverService"]/@*[local-name()="permission"])' -
)
direct_boot_aware=$(
    "$APK_ANALYZER" manifest print "$APK" \
        | xmllint --xpath \
            'string(//*[local-name()="service" and @*[local-name()="name"]="com.vowifi.sw.ObserverService"]/@*[local-name()="directBootAware"])' -
)
fgs_type=$(
    "$APK_ANALYZER" manifest print "$APK" \
        | xmllint --xpath \
            'string(//*[local-name()="service" and @*[local-name()="name"]="com.vowifi.sw.ObserverService"]/@*[local-name()="foregroundServiceType"])' -
)
fgs_subtype=$(
    "$APK_ANALYZER" manifest print "$APK" \
        | xmllint --xpath \
            'string(//*[local-name()="service" and @*[local-name()="name"]="com.vowifi.sw.ObserverService"]/*[local-name()="property" and @*[local-name()="name"]="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"]/@*[local-name()="value"])' -
)

if [ "$application_name" != "com.vowifi.sw.SoftVoWiFiApplication" ]; then
    printf 'application name must be com.vowifi.sw.SoftVoWiFiApplication; got %s\n' \
        "${application_name:-missing}" >&2
    exit 1
fi

if [ "$exported" != "true" ]; then
    printf 'ObserverService must be exported for the KernelSU watchdog; got %s\n' "${exported:-missing}" >&2
    exit 1
fi

if [ "$permission" != "android.permission.DUMP" ]; then
    printf 'ObserverService must restrict external starts with android.permission.DUMP; got %s\n' "${permission:-missing}" >&2
    exit 1
fi

if [ "$direct_boot_aware" != "false" ]; then
    printf 'ObserverService must remain credential-protected; directBootAware=%s\n' "${direct_boot_aware:-missing}" >&2
    exit 1
fi

if [ "$fgs_type" != "0x40000000" ] && [ "$fgs_type" != "specialUse" ]; then
    printf 'ObserverService must remain specialUse FGS; foregroundServiceType=%s\n' \
        "${fgs_type:-missing}" >&2
    exit 1
fi

if [ "$fgs_subtype" != "IMS registration observability" ]; then
    printf 'ObserverService FGS subtype must remain IMS registration observability; got %s\n' \
        "${fgs_subtype:-missing}" >&2
    exit 1
fi

network_state_count=$(
    "$APK_ANALYZER" manifest print "$APK" \
        | xmllint --xpath \
            'count(//*[local-name()="uses-permission" and @*[local-name()="name"]="android.permission.ACCESS_NETWORK_STATE"])' -
)
if [ "$network_state_count" != "1" ]; then
    printf 'APK must declare android.permission.ACCESS_NETWORK_STATE exactly once; got %s\n' \
        "${network_state_count:-missing}" >&2
    exit 1
fi

fsi_count=$(
    "$APK_ANALYZER" manifest print "$APK" \
        | xmllint --xpath \
            'count(//*[local-name()="uses-permission" and @*[local-name()="name"]="android.permission.USE_FULL_SCREEN_INTENT"])' -
)
if [ "$fsi_count" != "1" ]; then
    printf 'APK must declare android.permission.USE_FULL_SCREEN_INTENT exactly once; got %s\n' \
        "${fsi_count:-missing}" >&2
    exit 1
fi

post_notifications_count=$(
    "$APK_ANALYZER" manifest print "$APK" \
        | xmllint --xpath \
            'count(//*[local-name()="uses-permission" and @*[local-name()="name"]="android.permission.POST_NOTIFICATIONS"])' -
)
if [ "$post_notifications_count" != "1" ]; then
    printf 'APK must declare android.permission.POST_NOTIFICATIONS exactly once; got %s\n' \
        "${post_notifications_count:-missing}" >&2
    exit 1
fi

action_receiver_exported=$(
    "$APK_ANALYZER" manifest print "$APK" \
        | xmllint --xpath \
            'string(//*[local-name()="receiver" and @*[local-name()="name"]="com.vowifi.sw.IncomingCallActionReceiver"]/@*[local-name()="exported"])' -
)
if [ "$action_receiver_exported" != "false" ]; then
    printf 'IncomingCallActionReceiver must be non-exported; got %s\n' \
        "${action_receiver_exported:-missing}" >&2
    exit 1
fi

call_exported=$("$APK_ANALYZER" manifest print "$APK" | xmllint --xpath \
    'string(//*[local-name()="activity" and @*[local-name()="name"]="com.vowifi.sw.CallActivity"]/@*[local-name()="exported"])' -)
test "$call_exported" = false || { printf 'CallActivity must be non-exported\n' >&2; exit 1; }
microphone_exported=$("$APK_ANALYZER" manifest print "$APK" | xmllint --xpath \
    'string(//*[local-name()="service" and @*[local-name()="name"]="com.vowifi.sw.CallMicrophoneService"]/@*[local-name()="exported"])' -)
test "$microphone_exported" = false || { printf 'CallMicrophoneService must be non-exported\n' >&2; exit 1; }
microphone_type=$("$APK_ANALYZER" manifest print "$APK" | xmllint --xpath \
    'string(//*[local-name()="service" and @*[local-name()="name"]="com.vowifi.sw.CallMicrophoneService"]/@*[local-name()="foregroundServiceType"])' -)
case "$microphone_type" in
    microphone|0x80|0x00000080|128) ;;
    *) printf 'CallMicrophoneService must use microphone FGS\n' >&2; exit 1 ;;
esac
for call_permission in FOREGROUND_SERVICE FOREGROUND_SERVICE_MICROPHONE RECORD_AUDIO WAKE_LOCK MODIFY_AUDIO_SETTINGS; do
    declared=$("$APK_ANALYZER" manifest print "$APK" | xmllint --xpath \
        "count(//*[local-name()='uses-permission' and @*[local-name()='name']='android.permission.$call_permission'])" -)
    test "$declared" = 1 || { printf 'APK must declare android.permission.%s exactly once\n' "$call_permission" >&2; exit 1; }
done

if [ -n "$MODULE_PROP" ]; then
    test -f "$MODULE_PROP" || { printf 'missing module.prop: %s\n' "$MODULE_PROP" >&2; exit 1; }
    module_version=$(sed -n 's/^version=//p' "$MODULE_PROP")
    module_version_code=$(sed -n 's/^versionCode=//p' "$MODULE_PROP")
    apk_version=$("$APK_ANALYZER" manifest version-name "$APK")
    apk_version_code=$("$APK_ANALYZER" manifest version-code "$APK")
    if [ "$apk_version" != "$module_version" ] || [ "$apk_version_code" != "$module_version_code" ]; then
        printf 'APK/module version mismatch: apk=%s/%s module=%s/%s\n' \
            "$apk_version" "$apk_version_code" "$module_version" "$module_version_code" >&2
        exit 1
    fi
fi

printf 'APK manifest test passed\n'
