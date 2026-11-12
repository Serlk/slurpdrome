package dev.slurpdrome

import dev.slurpdrome.player.dlna.Ssdp
import dev.slurpdrome.player.dlna.UpnpClient
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.Collections

/** Protocol layer against a fake renderer: description, SOAP actions, parsing, DIDL. */
class DlnaTest {
    private lateinit var server: ServerSocket
    private val calls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())   // SOAPACTION to body
    private var state = "STOPPED"
    private var trackUri = ""
    private val base get() = "http://127.0.0.1:${server.localPort}"

    // Nested renderer inside a root device, relative control URLs, no URLBase — like many real devices.
    private val description = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<device><deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType><friendlyName>Hub</friendlyName><UDN>uuid:hub</UDN>
 <deviceList><device>
  <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
  <friendlyName>Living room &amp; kitchen</friendlyName><manufacturer>Acme</manufacturer><modelName>Box 2</modelName>
  <UDN>uuid:renderer-1</UDN>
  <serviceList>
   <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc/control</controlURL></service>
   <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>av/control</controlURL></service>
  </serviceList>
 </device></deviceList>
</device></root>"""

    private fun response(action: String, service: String, args: String) =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="$service">$args</u:${action}Response></s:Body></s:Envelope>"""

    /** Minimal HTTP/1.1 server: GET → description, POST → SOAP. One request per connection. */
    @Before fun start() {
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { sk ->
                    val input = sk.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = input.readLine() ?: return@use
                    val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                        .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    val len = headers["content-length"]?.toInt() ?: 0
                    val body = CharArray(len).also { var n = 0; while (n < len) { val r = input.read(it, n, len - n); if (r < 0) break; n += r } }.concatToString()
                    val path = requestLine.split(' ')[1]
                    val (code, out) = if (path == "/desc.xml") 200 to description else handleSoap(path, headers["soapaction"].orEmpty(), body)
                    val bytes = out.toByteArray()
                    sk.getOutputStream().apply {
                        write("HTTP/1.1 $code X\r\nContent-Type: text/xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }
    }

    private fun handleSoap(path: String, soapAction: String, body: String): Pair<Int, String> {
        val service = if (path == "/rc/control") UpnpClient.RC else UpnpClient.AVT
        val action = soapAction.trim('"').substringAfter('#')
        calls += action to body
        val args = when (action) {
            "SetAVTransportURI" -> { trackUri = Regex("<CurrentURI>(.*?)</CurrentURI>").find(body)!!.groupValues[1].replace("&amp;", "&"); state = "STOPPED"; "" }
            "Play" -> { state = "PLAYING"; "" }
            "Pause" -> { state = "PAUSED_PLAYBACK"; "" }
            "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>"
            "GetPositionInfo" -> "<Track>1</Track><TrackDuration>0:03:25</TrackDuration><TrackURI>${UpnpClient.escape(trackUri)}</TrackURI><RelTime>0:01:02.500</RelTime>"
            "GetVolume" -> "<CurrentVolume>37</CurrentVolume>"
            "Seek" -> if (state == "STOPPED") null else ""
            else -> ""
        }
        return if (args == null) 500 to """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail><UPnPError><errorCode>701</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
        else 200 to response(action, service, args)
    }

    @After fun stop() = server.close()

    @Test fun ssdpLocation() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nlocation:  http://192.168.1.20:49152/desc.xml \r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://192.168.1.20:49152/desc.xml", Ssdp.parseLocation(reply))
        assertEquals(null, Ssdp.parseLocation("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"))
    }

    @Test fun describeAndControl() {
        val client = UpnpClient(OkHttpClient())
        val r = client.describe("$base/desc.xml")
        assertNotNull(r); r!!
        assertEquals("Living room & kitchen", r.name)
        assertEquals("uuid:renderer-1", r.udn)
        assertEquals("Acme Box 2", r.model)
        assertEquals("$base/av/control", r.avTransportUrl)
        assertEquals("$base/rc/control", r.renderingControlUrl)

        val uri = "http://nav.local/rest/stream?id=1&u=me&t=abc"
        val didl = UpnpClient.didl("Rock & <Roll>", "AC/DC", "Album", null, uri, "audio/flac", 205_000)
        client.setUri(r, uri, didl)
        // The DIDL travels escaped inside the SOAP body and must survive as well-formed XML.
        val setBody = calls.last { it.first == "SetAVTransportURI" }.second
        assertTrue(setBody.contains("Rock &amp;amp; &amp;lt;Roll&amp;gt;"))
        assertTrue(setBody.contains("http-get:*:audio/flac:*"))

        // Seek before playback starts → UPnP 701 fault surfaces as an exception.
        assertTrue(runCatching { client.seek(r, 5_000) }.exceptionOrNull()?.message?.contains("701") == true)

        client.play(r)
        assertEquals("PLAYING", client.transportState(r))
        val pos = client.position(r)
        assertEquals(62_500L, pos.positionMs)
        assertEquals(205_000L, pos.durationMs)
        assertEquals(uri, pos.trackUri)
        client.seek(r, 3_723_000)
        assertTrue(calls.last().second.contains("<Target>1:02:03</Target>"))
        client.pause(r)
        assertEquals("PAUSED_PLAYBACK", client.transportState(r))
        assertEquals(37, client.volume(r))
        client.setVolume(r, 150)
        assertTrue(calls.last().second.contains("<DesiredVolume>100</DesiredVolume>"))
    }

    @Test fun times() {
        assertEquals(0L, UpnpClient.parseTime("NOT_IMPLEMENTED"))
        assertEquals(3_723_250L, UpnpClient.parseTime("1:02:03.25"))
        assertEquals("0:00:00", UpnpClient.formatTime(-5))
    }
}
