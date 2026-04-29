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
 * PrinterDiscoverySession for LibroPrinter kiosks.
 *
 * ## Robust two-phase discovery
 *
 * Android creates a fresh DiscoverySession for every print. To avoid the
 * "사용할 수 없습니다" race window during NSD discovery, we publish kiosks from
 * the persistent [KioskRegistry] **immediately** at session start, then refine
 * with live NSD results in the background.
 *
 * Lifecycle:
 *   1. onStartPrinterDiscovery → seed printer list from registry → start NSD
 *   2. NSD onServiceResolved → upsert into registry, refresh PrinterInfo
 *   3. onStartPrinterStateTracking → publish full capabilities
 *      (works even if NSD hasn't found the kiosk yet — endpoint comes from
 *       the registry)
 *   4. onStopPrinterDiscovery → stop NSD (registry persists)
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

    /** name → live endpoint resolved this session */
    private val liveEndpoints = mutableMapOf<String, Endpoint>()

    data class Endpoint(val host: String, val port: Int, val name: String)

    // ── PrinterDiscoverySession ─────────────────────────────────────────

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        Log.d(TAG, "onStartPrinterDiscovery (priority=${priorityList.size})")

        // Phase 1: re-publish kiosks from registry instantly. This prevents
        // the "unavailable" race where the system asks about a cached
        // printerId before NSD has time to rediscover.
        // If user has set a preferred printer, only that one is published.
        val preferred = PluginPrefs.getPreferredPrinter(service)
        val saved = KioskRegistry.getAll(service).let { all ->
            if (preferred.isNotEmpty()) all.filter { it.name == preferred } else all
        }
        if (saved.isNotEmpty()) {
            val infos = saved.map { kiosk ->
                liveEndpoints[kiosk.name] = Endpoint(kiosk.host, kiosk.port, kiosk.name)
                buildPrinterInfo(kiosk.name)
            }
            addPrinters(infos)
            Log.i(TAG, "Phase1: published ${infos.size} kiosk(s) from registry (preferred=\"$preferred\")")
        }

        // Phase 2: live NSD discovery
        startNsdDiscovery()
    }

    override fun onStopPrinterDiscovery() {
        Log.d(TAG, "onStopPrinterDiscovery")
        stopNsdDiscovery()
    }

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}

    /**
     * Called when the system needs the printer's full capabilities (paper
     * sizes, resolutions, etc.). Must always succeed if we know the printer
     * exists in our registry — silent return = "unavailable" in the picker.
     */
    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        Log.d(TAG, "onStartPrinterStateTracking: ${printerId.localId}")
        val name = nameFromLocalId(printerId.localId) ?: return
        addPrinters(listOf(buildPrinterInfo(name, withCapabilities = true)))
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {}

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopNsdDiscovery()
        liveEndpoints.clear()
    }

    // ── PrinterInfo builder ─────────────────────────────────────────────

    /** Build PrinterInfo. Always advertises capabilities so picker stays usable. */
    private fun buildPrinterInfo(name: String, withCapabilities: Boolean = true): PrinterInfo {
        val printerId = service.generatePrinterId(localIdFor(name))
        val displayName = "$name (72mm)"
        val builder = PrinterInfo.Builder(printerId, displayName, PrinterInfo.STATUS_IDLE)

        if (withCapabilities) {
            val resolution = PrintAttributes.Resolution("203dpi", "203 DPI", 203, 203)
            // 72mm = 2835 mils
            val r200 = PrintAttributes.MediaSize("RECEIPT_72x200", "72mm x 200mm", 2835, 7874)
            val r300 = PrintAttributes.MediaSize("RECEIPT_72x300", "72mm x 300mm", 2835, 11811)
            val r600 = PrintAttributes.MediaSize("RECEIPT_72x600", "72mm x 600mm", 2835, 23622)
            val caps = PrinterCapabilitiesInfo.Builder(printerId)
                .addMediaSize(r200, true)
                .addMediaSize(r300, false)
                .addMediaSize(r600, false)
                .addResolution(resolution, true)
                .setColorModes(
                    PrintAttributes.COLOR_MODE_MONOCHROME,
                    PrintAttributes.COLOR_MODE_MONOCHROME
                )
                .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
                .build()
            builder.setCapabilities(caps)
        }
        return builder.build()
    }

    // ── localId <-> name mapping ────────────────────────────────────────

    private fun localIdFor(name: String): String = "libro-net-$name"
    private fun nameFromLocalId(localId: String): String? =
        if (localId.startsWith("libro-net-")) localId.removePrefix("libro-net-") else null

    // ── NSD live discovery ──────────────────────────────────────────────

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
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.contains(PRINTER_NAME_PREFIX, ignoreCase = true)) return
                // Apply preferred-printer filter (if set, only resolve that one)
                val preferred = PluginPrefs.getPreferredPrinter(service)
                if (preferred.isNotEmpty() && info.serviceName != preferred) return
                mgr.resolveService(info, createResolveListener())
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                Log.d(TAG, "NSD lost: ${info.serviceName}")
                // Keep the registry entry — kiosk may come back. PrintFramework
                // can mark it as unreachable when the actual print fails.
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
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "NSD resolve failed: ${info.serviceName}, error=$errorCode")
        }

        @Suppress("DEPRECATION")
        override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            val port = info.port
            val name = info.serviceName
            Log.i(TAG, "NSD resolved: $name -> $host:$port")

            val endpoint = Endpoint(host, port, name)
            liveEndpoints[name] = endpoint

            KioskRegistry.upsert(
                service,
                KioskRegistry.Kiosk(name, host, port, System.currentTimeMillis())
            )

            // generatePrinterId / addPrinters must run on main thread
            mainHandler.post {
                addPrinters(listOf(buildPrinterInfo(name, withCapabilities = true)))
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

    // ── Endpoint lookup for the print service ───────────────────────────

    /**
     * Resolve a printerId to an endpoint at print time. Tries the live NSD
     * map first; falls back to the persistent registry so prints can succeed
     * even when NSD discovery hasn't completed yet (common on session 2+).
     */
    fun getEndpoint(printerId: PrinterId): Endpoint? {
        val name = nameFromLocalId(printerId.localId) ?: return null
        liveEndpoints[name]?.let { return it }
        val saved = KioskRegistry.getAll(service).firstOrNull { it.name == name }
        return saved?.let { Endpoint(it.host, it.port, it.name) }
    }
}
