package com.betona.libroprintplugin

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrinterDiscoverySession
import android.util.Log

/**
 * Discovers LibroPrinter kiosks on the local network via mDNS (_ipp._tcp).
 * Reports 72mm thermal paper so the phone lays out content at the correct width.
 */
class LibroNetDiscoverySession(
    private val service: LibroNetPrintService
) : PrinterDiscoverySession() {

    companion object {
        private const val TAG = "LibroNetDiscovery"
        private const val SERVICE_TYPE = "_ipp._tcp."
        private const val PRINTER_NAME_PREFIX = "LibroPrinter"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // Track discovered printers: localId -> (host, port)
    private val discoveredPrinters = mutableMapOf<String, PrinterEndpoint>()

    data class PrinterEndpoint(val host: String, val port: Int, val name: String)

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        Log.d(TAG, "onStartPrinterDiscovery")
        startNsdDiscovery()
    }

    override fun onStopPrinterDiscovery() {
        Log.d(TAG, "onStopPrinterDiscovery")
        stopNsdDiscovery()
    }

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        Log.d(TAG, "onStartPrinterStateTracking: ${printerId.localId}")

        val endpoint = discoveredPrinters[printerId.localId] ?: return

        val resolution = PrintAttributes.Resolution("203dpi", "203 DPI", 203, 203)

        // 72mm paper sizes: width = 72mm = 2835 mils
        val receipt200 = PrintAttributes.MediaSize(
            "RECEIPT_72x200", "72mm x 200mm", 2835, 7874
        )
        val receipt300 = PrintAttributes.MediaSize(
            "RECEIPT_72x300", "72mm x 300mm", 2835, 11811
        )
        val receipt600 = PrintAttributes.MediaSize(
            "RECEIPT_72x600", "72mm x 600mm", 2835, 23622
        )

        val capabilities = PrinterCapabilitiesInfo.Builder(printerId)
            .addMediaSize(receipt200, true)   // default: 200mm
            .addMediaSize(receipt300, false)
            .addMediaSize(receipt600, false)
            .addResolution(resolution, true)
            .setColorModes(
                PrintAttributes.COLOR_MODE_MONOCHROME,
                PrintAttributes.COLOR_MODE_MONOCHROME
            )
            .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
            .build()

        val displayName = "${endpoint.name} (72mm)"
        addPrinters(listOf(
            PrinterInfo.Builder(printerId, displayName, PrinterInfo.STATUS_IDLE)
                .setCapabilities(capabilities)
                .build()
        ))
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {}

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopNsdDiscovery()
        discoveredPrinters.clear()
    }

    // ── NSD Discovery ────────────────────────────────────────────────────

    private fun startNsdDiscovery() {
        val mgr = service.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = mgr

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped")
            }

            @Suppress("DEPRECATION")
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceName.contains(PRINTER_NAME_PREFIX, ignoreCase = true)) {
                    mgr.resolveService(serviceInfo, createResolveListener())
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD lost: ${serviceInfo.serviceName}")
                val localId = "libro-net-${serviceInfo.serviceName}"
                discoveredPrinters.remove(localId)
                // PrinterDiscoverySession doesn't have removePrinters, printer will appear unavailable
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD stop failed: $errorCode")
            }
        }

        discoveryListener = listener
        mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Suppress("DEPRECATION")
    private fun createResolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "NSD resolve failed: ${serviceInfo.serviceName}, error=$errorCode")
        }

        @Suppress("DEPRECATION")
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val port = serviceInfo.port
            val name = serviceInfo.serviceName
            Log.i(TAG, "NSD resolved: $name -> $host:$port")

            val localId = "libro-net-$name"
            discoveredPrinters[localId] = PrinterEndpoint(host, port, name)

            // generatePrinterId() and addPrinters() must be called on main thread
            mainHandler.post {
                val printerId = service.generatePrinterId(localId)
                val displayName = "$name (72mm)"
                addPrinters(listOf(
                    PrinterInfo.Builder(printerId, displayName, PrinterInfo.STATUS_IDLE).build()
                ))
            }
        }
    }

    private fun stopNsdDiscovery() {
        discoveryListener?.let { listener ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.e(TAG, "NSD stop error", e)
            }
        }
        discoveryListener = null
    }

    /** Look up the endpoint for a printer ID. Used by the PrintService. */
    fun getEndpoint(printerId: PrinterId): PrinterEndpoint? {
        return discoveredPrinters[printerId.localId]
    }
}
