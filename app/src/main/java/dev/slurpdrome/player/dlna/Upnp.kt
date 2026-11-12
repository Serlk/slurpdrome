package dev.slurpdrome.player.dlna

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** A UPnP/DLNA MediaRenderer (receiver, smart speaker, TV) found on the local network. */
data class Renderer(
    val udn: String,
    val name: String,
    val model: String?,
    val location: String,
    val avTransportUrl: String,
    val renderingControlUrl: String?,
)

/** SSDP discovery: one M-SEARCH, collect the unicast replies for [timeoutMs]. */
object Ssdp {
    private const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** [configure] can bind the socket to a network (e.g. Wi-Fi when a VPN is up). */
    fun search(timeoutMs: Int = 3000, configure: (DatagramSocket) -> Unit = {}): Set<String> {
        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $RENDERER\r\n\r\n".toByteArray()
        val group = InetAddress.getByName("239.255.255.250")
        val found = linkedSetOf<String>()
        DatagramSocket().use { s ->
            configure(s)
            s.soTimeout = 300
            // Two sends: UDP is lossy and some devices ignore the first probe.
            repeat(2) { s.send(DatagramPacket(msg, msg.size, group, 1900)) }
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                parseLocation(String(p.data, 0, p.length))?.let(found::add)
            }
        }
        return found
    }

    /** LOCATION header of an SSDP reply (case-insensitive, as devices vary). */
    fun parseLocation(reply: String): String? = reply.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("location:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http") }
}

/** Device description + SOAP control (AVTransport, RenderingControl). Pure JVM — unit-testable. */
class UpnpClient(private val http: OkHttpClient) {

    fun describe(location: String): Renderer? {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.string() ?: return null
        }
        return parseDescription(xml, location)
    }

    // ---- AVTransport ----
    fun setUri(r: Renderer, uri: String, didl: String) =
        av(r, "SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to didl)

    fun setNextUri(r: Renderer, uri: String, didl: String) =
        av(r, "SetNextAVTransportURI", "NextURI" to uri, "NextURIMetaData" to didl)

    fun play(r: Renderer) = av(r, "Play", "Speed" to "1")
    fun pause(r: Renderer) = av(r, "Pause")
    fun stop(r: Renderer) = av(r, "Stop")
    fun seek(r: Renderer, ms: Long) = av(r, "Seek", "Unit" to "REL_TIME", "Target" to formatTime(ms))

    /** CurrentTransportState: PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT. */
    fun transportState(r: Renderer): String? = av(r, "GetTransportInfo")["CurrentTransportState"]

    data class Position(val positionMs: Long, val durationMs: Long, val trackUri: String?)

    fun position(r: Renderer): Position {
        val m = av(r, "GetPositionInfo")
        return Position(parseTime(m["RelTime"]), parseTime(m["TrackDuration"]), m["TrackURI"]?.takeIf { it.isNotBlank() })
    }

    // ---- RenderingControl ----
    fun volume(r: Renderer): Int? = r.renderingControlUrl?.let {
        soap(it, RC, "GetVolume", "InstanceID" to "0", "Channel" to "Master")["CurrentVolume"]?.toIntOrNull()
    }

    fun setVolume(r: Renderer, v: Int) {
        r.renderingControlUrl?.let { soap(it, RC, "SetVolume", "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to v.coerceIn(0, 100).toString()) }
    }

    fun setMute(r: Renderer, mute: Boolean) {
        r.renderingControlUrl?.let { soap(it, RC, "SetMute", "InstanceID" to "0", "Channel" to "Master", "DesiredMute" to if (mute) "1" else "0") }
    }

    private fun av(r: Renderer, action: String, vararg args: Pair<String, String>) =
        soap(r.avTransportUrl, AVT, action, "InstanceID" to "0", *args)

    /** One SOAP call; returns the response arguments, throws [UpnpException] on a fault. */
    fun soap(url: String, service: String, action: String, vararg args: Pair<String, String>): Map<String, String> {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:$action xmlns:u=\"$service\">")
            for ((k, v) in args) append("<$k>").append(escape(v)).append("</$k>")
            append("</u:$action></s:Body></s:Envelope>")
        }
        val req = Request.Builder().url(url)
            .header("SOAPACTION", "\"$service#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val code = Regex("<errorCode>(\\d+)</errorCode>").find(text)?.groupValues?.get(1)
                throw UpnpException("$action failed: HTTP ${resp.code}${code?.let { ", UPnP error $it" } ?: ""}")
            }
            return parseResponse(text)
        }
    }

    companion object {
        const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"

        private fun dom(xml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement

        private fun Element.children(local: String) = (0 until childNodes.length).map { childNodes.item(it) }
            .filterIsInstance<Element>().filter { (it.localName ?: it.tagName).substringAfter(':') == local }

        private fun Element.text(local: String) = children(local).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

        /** Finds the MediaRenderer device (possibly nested) and its control URLs. */
        fun parseDescription(xml: String, location: String): Renderer? {
            val root = dom(xml)
            val base = root.text("URLBase") ?: location
            fun resolve(u: String) = runCatching { URI(base).resolve(u.trim()).toString() }.getOrDefault(u)
            fun devices(e: Element): List<Element> = e.children("device").flatMap { d ->
                listOf(d) + d.children("deviceList").flatMap { devices(it) }
            }
            val device = devices(root).firstOrNull { it.text("deviceType")?.contains("MediaRenderer") == true } ?: return null
            val services = device.children("serviceList").flatMap { it.children("service") }
            fun control(type: String) = services.firstOrNull { it.text("serviceType")?.contains(type) == true }?.text("controlURL")?.let(::resolve)
            val av = control("AVTransport") ?: return null
            return Renderer(
                udn = device.text("UDN") ?: location,
                name = device.text("friendlyName") ?: URI(location).host,
                model = listOfNotNull(device.text("manufacturer"), device.text("modelName")).joinToString(" ").ifBlank { null },
                location = location,
                avTransportUrl = av,
                renderingControlUrl = control("RenderingControl"),
            )
        }

        /** Arguments of `<u:ActionResponse>` as a flat map. */
        fun parseResponse(xml: String): Map<String, String> {
            if (xml.isBlank()) return emptyMap()
            val body = dom(xml).children("Body").firstOrNull() ?: return emptyMap()
            val resp = (0 until body.childNodes.length).map { body.childNodes.item(it) }.filterIsInstance<Element>().firstOrNull() ?: return emptyMap()
            return (0 until resp.childNodes.length).map { resp.childNodes.item(it) }.filterIsInstance<Element>()
                .associate { (it.localName ?: it.tagName) to it.textContent.orEmpty() }
        }

        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

        /** "H:MM:SS(.fff)" → ms; NOT_IMPLEMENTED / empty → 0. */
        fun parseTime(s: String?): Long {
            val p = s?.trim()?.split(':') ?: return 0
            if (p.size != 3) return 0
            val h = p[0].toLongOrNull() ?: return 0
            val m = p[1].toLongOrNull() ?: return 0
            val sec = p[2].toDoubleOrNull() ?: return 0
            return h * 3_600_000 + m * 60_000 + (sec * 1000).toLong()
        }

        fun formatTime(ms: Long): String {
            val t = ms.coerceAtLeast(0) / 1000
            return "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60)
        }

        /** DIDL-Lite item so renderers can show title/artist/cover; protocolInfo carries the mime type. */
        fun didl(title: String, artist: String?, album: String?, artUrl: String?, uri: String, mime: String, durationMs: Long): String {
            val dur = if (durationMs > 0) " duration=\"${formatTime(durationMs)}.000\"" else ""
            val xml = buildString {
                append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
                append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
                append("<dc:title>").append(escape(title)).append("</dc:title>")
                artist?.let { append("<dc:creator>").append(escape(it)).append("</dc:creator><upnp:artist>").append(escape(it)).append("</upnp:artist>") }
                album?.let { append("<upnp:album>").append(escape(it)).append("</upnp:album>") }
                artUrl?.let { append("<upnp:albumArtURI>").append(escape(it)).append("</upnp:albumArtURI>") }
                append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
                append("<res protocolInfo=\"http-get:*:").append(escape(mime)).append(":*\"").append(dur).append(">").append(escape(uri)).append("</res>")
                append("</item></DIDL-Lite>")
            }
            return xml
        }
    }
}

class UpnpException(msg: String) : Exception(msg)
