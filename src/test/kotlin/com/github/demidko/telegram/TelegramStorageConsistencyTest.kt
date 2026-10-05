package com.github.demidko.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.entities.ChatId
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

/** In-memory channel with immutable uploaded files and a mutable description reference. */
internal class StatefulTelegram : AutoCloseable {
  private val serializer = MapSerializer(String.serializer(), String.serializer())
  val files = ConcurrentHashMap<String, ByteArray>().apply {
    put("initial", Cbor.encodeToByteArray(serializer, mapOf("key" to "value-file")))
  }
  @Volatile var description: String? = "initial"
  @Volatile var failUpload = false
  @Volatile var failDescription = false
  @Volatile var descriptionGate: CountDownLatch? = null
  val descriptionEntered = CountDownLatch(1)
  val uploads = AtomicInteger()
  val updates = AtomicInteger()
  private val server = MockWebServer()

  init {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val result = when (request.requestUrl!!.encodedPath) {
          "/botlocal/getChat" -> """{"id":1,"type":"channel"${description?.let { ",\"description\":\"$it\"" } ?: ""}}"""
          "/botlocal/getFile" -> {
            val id = request.requestUrl!!.queryParameter("file_id")!!
            if (!files.containsKey(id)) return MockResponse().setResponseCode(404)
            """{"file_id":"$id","file_unique_id":"$id","file_path":"$id"}"""
          }
          "/botlocal/sendDocument" -> {
            val id = "upload-${uploads.incrementAndGet()}"
            if (failUpload) return MockResponse().setResponseCode(500)
            val body = request.body.clone().readByteString()
            val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=")
            val header = body.indexOf("name=\"document\"".encodeUtf8())
            check(header >= 0)
            val start = body.indexOf("\r\n\r\n".encodeUtf8(), header) + 4
            val end = body.indexOf("\r\n--$boundary".encodeUtf8(), start)
            files[id] = body.substring(start, end).toByteArray()
            """{"message_id":1,"date":0,"chat":{"id":1,"type":"channel"},"document":{"file_id":"$id","file_unique_id":"$id"}}"""
          }
          "/botlocal/setChatDescription" -> {
            updates.incrementAndGet()
            descriptionEntered.countDown()
            check(descriptionGate?.await(10, SECONDS) != false)
            if (failDescription) return MockResponse().setResponseCode(500)
            description = request.body.clone().readUtf8().split('&')
              .first { it.startsWith("description=") }.substringAfter('=')
              .let { URLDecoder.decode(it, Charsets.UTF_8) }.ifEmpty { null }
            "true"
          }
          else -> {
            val id = request.requestUrl!!.encodedPath.removePrefix("/file/botlocal/")
            return files[id]?.let { MockResponse().setBody(Buffer().write(it)) }
              ?: MockResponse().setResponseCode(404)
          }
        }
        return MockResponse().setHeader("Content-Type", "application/json")
          .setBody("""{"ok":true,"result":$result}""")
      }
    }
    server.start()
  }

  val apiUrl get() = server.url("/").toString()

  fun open() = TelegramStorage(
    bot { token = "local"; apiUrl = server.url("/").toString() },
    ChatId.fromId(1), String.serializer(), String.serializer()
  )

  fun publishedIndex() = Cbor.decodeFromByteArray(serializer, files.getValue(description!!))

  override fun close() = server.shutdown()
}

class TelegramStorageConsistencyTest {
  @Test
  fun `last close republishes its snapshot even if another instance changed the channel`() = StatefulTelegram().use { api ->
    val first = api.open()
    api.open().use { second -> second.remove("key") }
    assertEquals(emptyMap<String, String>(), api.publishedIndex())
    first.close()
    assertEquals(mapOf("key" to "value-file"), api.publishedIndex())
    api.open().use { reopened -> assertTrue(reopened.containsKey("key")) }
  }

  @Test
  fun `close republishes if the previously loaded file disappears`() = StatefulTelegram().use { api ->
    val storage = api.open()
    api.files.remove("initial")
    storage.close()
    assertNotEquals("initial", api.description)
    assertEquals(mapOf("key" to "value-file"), api.publishedIndex())
  }

  @Test
  fun `close republishes if the description is externally cleared`() = StatefulTelegram().use { api ->
    val storage = api.open()
    api.description = null
    storage.close()
    assertNotNull(api.description)
    assertEquals(mapOf("key" to "value-file"), api.publishedIndex())
  }

  @Test
  fun `close waits for acknowledgement of the description update`() = StatefulTelegram().use { api ->
    val storage = api.open()
    val gate = CountDownLatch(1)
    api.descriptionGate = gate
    val caller = Executors.newSingleThreadExecutor()
    try {
      val closing = caller.submit { storage.close() }
      assertTrue(api.descriptionEntered.await(10, SECONDS))
      assertFalse(closing.isDone)
      gate.countDown()
      closing.get(10, SECONDS)
      assertEquals(mapOf("key" to "value-file"), api.publishedIndex())
    } finally {
      gate.countDown()
      caller.shutdownNow()
    }
  }

  @Test
  fun `failed value upload can be retried without losing the original reference`() = StatefulTelegram().use { api ->
    api.open().use { storage ->
      api.failUpload = true
      assertThrows(ExecutionException::class.java) { storage["key"] = "new value" }
      api.failUpload = false
      storage["key"] = "new value"
    }
    assertEquals(mapOf("key" to "upload-2"), api.publishedIndex())
  }

  @Test
  fun `unchanged close still reports upload failure and repeated close does not retry`() = StatefulTelegram().use { api ->
    val storage = api.open()
    try {
      api.failUpload = true
      assertThrows(ExecutionException::class.java) { storage.close() }
      api.failUpload = false
      storage.close()
      assertEquals(1, api.uploads.get())
      assertEquals(0, api.updates.get())
      assertEquals("initial", api.description)
    } finally {
      // Preserve the baseline's registered hook after failed close, then clean up this fixture.
      val hook = storage.javaClass.getDeclaredField("shutdownHook").apply { isAccessible = true }
      assertTrue(Runtime.getRuntime().removeShutdownHook(hook.get(storage) as Thread))
      // Baseline close leaves its executor alive after failure. Do not leak it in the test JVM.
      val field = storage.javaClass.getDeclaredField("atomicExecutor").apply { isAccessible = true }
      (field.get(storage) as ExecutorService).shutdownNow()
    }
  }

  @Test
  fun `description update failure is reported and leaves the old index published`() = StatefulTelegram().use { api ->
    val storage = api.open()
    try {
      api.failDescription = true
      assertThrows(ExecutionException::class.java) { storage.close() }
      assertEquals(1, api.uploads.get())
      assertEquals(1, api.updates.get())
      assertEquals("initial", api.description)
    } finally {
      val field = storage.javaClass.getDeclaredField("atomicExecutor").apply { isAccessible = true }
      (field.get(storage) as ExecutorService).shutdownNow()
    }
  }
}
