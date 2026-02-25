package com.betona.printdriver

import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrinterDiscoverySession
import android.util.Log
import java.io.File

/**
 * Discovers the built-in JY-P1000 thermal printer.
 * Reports 80mm continuous roll paper so apps format content for 80mm width.
 */
class LibroDiscoverySession(
    private val service: LibroPrintService
) : PrinterDiscoverySession() {

    companion object {
        private const val TAG = "LibroDiscovery"
        const val PRINTER_LOCAL_ID = "jy-p1000-thermal"
        private const val PRINTER_NAME = "JY-P1000 감열 프린터"
    }

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        Log.d(TAG, "onStartPrinterDiscovery")
        val printerId = service.generatePrinterId(PRINTER_LOCAL_ID)
        val status = if (File(DevicePrinter.DEVICE_PATH).exists()) {
            PrinterInfo.STATUS_IDLE
        } else {
            PrinterInfo.STATUS_UNAVAILABLE
        }
        addPrinters(listOf(PrinterInfo.Builder(printerId, PRINTER_NAME, status).build()))
    }

    override fun onStopPrinterDiscovery() {}
    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        Log.d(TAG, "onStartPrinterStateTracking")

        val resolution = PrintAttributes.Resolution("203dpi", "203 DPI", 203, 203)

        // Paper width: 75mm (2953 mils) - ~70% of 60mm text size
        val receipt150 = PrintAttributes.MediaSize(
            "RECEIPT_75x150",
            "75mm x 150mm",
            2953, 5906     // 150mm
        )
        val receipt300 = PrintAttributes.MediaSize(
            "RECEIPT_75x300",
            "75mm x 300mm",
            2953, 11811    // 300mm
        )
        val receipt600 = PrintAttributes.MediaSize(
            "RECEIPT_75x600",
            "75mm x 600mm",
            2953, 23622    // 600mm
        )

        val capabilities = PrinterCapabilitiesInfo.Builder(printerId)
            .addMediaSize(receipt150, true)     // default: 150mm
            .addMediaSize(receipt300, false)
            .addMediaSize(receipt600, false)
            .addResolution(resolution, true)
            .setColorModes(
                PrintAttributes.COLOR_MODE_MONOCHROME,
                PrintAttributes.COLOR_MODE_MONOCHROME
            )
            .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
            .build()

        val status = if (File(DevicePrinter.DEVICE_PATH).exists()) {
            PrinterInfo.STATUS_IDLE
        } else {
            PrinterInfo.STATUS_UNAVAILABLE
        }

        addPrinters(listOf(
            PrinterInfo.Builder(printerId, PRINTER_NAME, status)
                .setCapabilities(capabilities)
                .build()
        ))
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {}
    override fun onDestroy() {}
}
