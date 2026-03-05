#!/system/bin/sh
# LibroPrintDriver boot-time configuration for A40i (Android 7)
# Install to: /system/bin/configure_print.sh
sleep 15
settings put secure enabled_print_services com.android.printdriver/com.betona.printdriver.LibroPrintService
settings put secure disabled_print_services ""
pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION
pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION
settings put global webview_provider com.android.chrome
# Toggle accessibility off→on to force service bind
settings put secure enabled_accessibility_services ""
settings put secure accessibility_enabled 0
sleep 1
settings put secure enabled_accessibility_services com.greenmango.remote/com.greenmango.remote.InputService
settings put secure accessibility_enabled 1
settings put secure high_text_contrast_enabled 0
dumpsys deviceidle whitelist +com.greenmango.remote
log -t PrintConfig "Print service configured"
