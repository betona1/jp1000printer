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

        // Paper width: 72mm (2835 mils) = actual thermal print width (576px @ 203dpi)
        val receipt72x150 = PrintAttributes.MediaSize(
            "RECEIPT_72x150",
            "72mm x 150mm",
            2835, 5906     // 72mm x 150mm
        )
        val receipt72x75 = PrintAttributes.MediaSize(
            "RECEIPT_72x75",
            "72mm x 75mm",
            2835, 2953     // 72mm x 75mm
        )
        val receipt72x200 = PrintAttributes.MediaSize(
            "RECEIPT_72x200",
            "72mm x 200mm",
            2835, 7874     // 72mm x 200mm
        )
        val receipt72x600 = PrintAttributes.MediaSize(
            "RECEIPT_72x600",
            "72mm x 600mm",
            2835, 23622    // 72mm x 600mm
        )
        val receipt72x1200 = PrintAttributes.MediaSize(
            "RECEIPT_72x1200",
            "72mm x 1200mm",
            2835, 47244    // 72mm x 1200mm
        )

        val capabilities = PrinterCapabilitiesInfo.Builder(printerId)
            .addMediaSize(receipt72x150, true)       // default
            .addMediaSize(receipt72x75, false)
            .addMediaSize(receipt72x200, false)
            .addMediaSize(receipt72x600, false)
            .addMediaSize(receipt72x1200, false)
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
