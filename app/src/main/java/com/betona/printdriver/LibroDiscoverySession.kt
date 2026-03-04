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

        // Paper width: A4 (210mm = 8268 mils) so apps format content at readable size.
        // The driver scales the rendered PDF to 576px (72mm) thermal width.
        val receiptA4 = PrintAttributes.MediaSize(
            "RECEIPT_A4x297",
            "A4 x 297mm",
            8268, 11693    // 210mm x 297mm (A4)
        )
        val receiptA4Long = PrintAttributes.MediaSize(
            "RECEIPT_A4x600",
            "A4 x 600mm",
            8268, 23622    // 210mm x 600mm
        )
        val receiptA4XLong = PrintAttributes.MediaSize(
            "RECEIPT_A4x1200",
            "A4 x 1200mm",
            8268, 47244    // 210mm x 1200mm
        )

        val capabilities = PrinterCapabilitiesInfo.Builder(printerId)
            .addMediaSize(receiptA4, true)       // default: A4 height
            .addMediaSize(receiptA4Long, false)
            .addMediaSize(receiptA4XLong, false)
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
