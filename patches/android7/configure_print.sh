#!/system/bin/sh
# LibroPrintDriver boot-time configuration for A40i (Android 7)
# Install to: /system/bin/configure_print.sh
sleep 15
settings put secure enabled_print_services com.android.printdriver/com.betona.printdriver.LibroPrintService
settings put secure disabled_print_services ""
pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION
pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION
log -t PrintConfig "Print service configured"
