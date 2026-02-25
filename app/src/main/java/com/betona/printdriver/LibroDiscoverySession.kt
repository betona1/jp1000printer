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
 * Reports a single printer with 80mm receipt paper capabilities.
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

        // Check if device exists
        val status = if (File(DevicePrinter.DEVICE_PATH).exists()) {
            PrinterInfo.STATUS_IDLE
        } else {
            PrinterInfo.STATUS_UNAVAILABLE
        }

        val info = PrinterInfo.Builder(printerId, PRINTER_NAME, status).build()
        addPrinters(listOf(info))
    }

    override fun onStopPrinterDiscovery() {
        Log.d(TAG, "onStopPrinterDiscovery")
    }

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
        Log.d(TAG, "onValidatePrinters: ${printerIds.size}")
    }

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        Log.d(TAG, "onStartPrinterStateTracking: $printerId")

        // Report full capabilities
        val resolution = PrintAttributes.Resolution("203dpi", "203 DPI", 203, 203)

        // 80mm receipt paper: ~72mm printable width, continuous roll
        // MediaSize uses mils (1/1000 inch): 80mm ≈ 3150 mils
        // Max print length 300mm ≈ 11811 mils
        val receiptPaper = PrintAttributes.MediaSize(
            "RECEIPT_80MM",
            "80mm 감열지",
            3150,   // width: 80mm
            11811   // height: 300mm (max)
        )

        // Smaller label size for call number labels
        val labelPaper = PrintAttributes.MediaSize(
            "LABEL_80x50",
            "80x50mm 라벨",
            3150,   // width: 80mm
            1969    // height: 50mm
        )

        val capabilities = PrinterCapabilitiesInfo.Builder(printerId)
            .addMediaSize(receiptPaper, true)   // default
            .addMediaSize(labelPaper, false)
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

        val printerInfo = PrinterInfo.Builder(printerId, PRINTER_NAME, status)
            .setCapabilities(capabilities)
            .build()

        addPrinters(listOf(printerInfo))
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {
        Log.d(TAG, "onStopPrinterStateTracking")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
    }
}
