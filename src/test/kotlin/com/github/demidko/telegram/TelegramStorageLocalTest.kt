package com.github.demidko.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.entities.ChatId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

internal class LocalTelegram(
  val initial: Map<String, String>? = mapOf("key" to "value-file"),
  val missingIndex: Boolean = false,
  val initialBytes: ByteArray? = null,
) : AutoCloseable {
  val requests = CopyOnWriteArrayList<RecordedRequest>()
  val server = MockWebServer()
  val serializer = MapSerializer(String.serializer(), String.serializer())

  init {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val result = when (request.path?.substringBefore("?")) {
          "/botlocal/getChat" -> """{"id":1,"type":"channel"${if (initial == null) "" else ",\"description\":\"index\""}}"""
          "/botlocal/getFile" -> if (request.requestUrl?.queryParameter("file_id") == "value-file")
            """{"file_id":"value-file","file_unique_id":"value-file","file_path":"value.cbor"}"""
            else if (missingIndex) return MockResponse().setResponseCode(404)
            else """{"file_id":"index","file_unique_id":"index","file_path":"index.cbor"}"""
          "/file/botlocal/value.cbor" -> return MockResponse().setBody(
            Buffer().write(Cbor.encodeToByteArray(String.serializer(), "stored value"))
          )
          "/file/botlocal/index.cbor" -> return MockResponse().setBody(
            Buffer().write(initialBytes ?: Cbor.encodeToByteArray(serializer, initial!!))
          )
          "/botlocal/sendDocument" -> """{"message_id":1,"date":0,"chat":{"id":1,"type":"channel"},"document":{"file_id":"new-file","file_unique_id":"new-file"}}"""
          "/botlocal/setChatDescription" -> "true"
          else -> return MockResponse().setResponseCode(404)
        }
        return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true,"result":$result}""")
      }
    }
    server.start()
  }

  fun open(): TelegramStorage<String, String> = open(String.serializer(), String.serializer())

  fun <K, V> open(keys: KSerializer<K>, values: KSerializer<V>): TelegramStorage<K, V> = TelegramStorage(
    bot { token = "local"; apiUrl = server.url("/").toString() },
    ChatId.fromId(1), keys, values
  )

  fun count(method: String) = requests.count { it.path?.substringBefore("?") == "/botlocal/$method" }

  fun assertUploadedIndex(expected: Map<String, String>) {
    val upload = requests.last { it.path?.substringBefore("?") == "/botlocal/sendDocument" }.body.clone().readByteArray()
    val encoded = Cbor.encodeToByteArray(serializer, expected)
    assertTrue(upload.asList().windowed(encoded.size).any { it == encoded.asList() }, "Expected CBOR index in upload")
  }

  override fun close() = server.shutdown()
}

class TelegramStorageLocalTest {
  @Serializable
  class MutableKey(val id: String, var label: String) {
    override fun equals(other: Any?) = other is MutableKey && id == other.id
    override fun hashCode() = id.hashCode()
  }

  @Test
  fun `mutable serialized key metadata is persisted even when equality is unchanged`() {
    val serializer = MapSerializer(MutableKey.serializer(), String.serializer())
    val original = Cbor.encodeToByteArray(serializer, mapOf(MutableKey("key", "before") to "value-file"))
    LocalTelegram(initialBytes = original).use { api ->
      api.open(MutableKey.serializer(), String.serializer()).use { storage ->
        storage.keys.first().label = "after"
      }
      assertEquals(1, api.count("sendDocument"))
      val upload = api.requests.last { it.path?.substringBefore("?") == "/botlocal/sendDocument" }.body.clone()
      assertTrue(upload.readUtf8().contains("after"))
    }
  }

  @Test
  fun `read only close republishes the index and is idempotent`() = LocalTelegram().use { api ->
    val storage = api.open()
    assertEquals(1, storage.size)
    assertTrue(storage.containsKey("key"))
    assertFalse(storage.isEmpty())
    assertEquals(setOf("key"), storage.keys)
    assertNull(storage["absent"])
    assertEquals("stored value", storage["key"])
    storage.remove("absent")
    storage.close()
    storage.close()
    assertEquals(1, api.count("sendDocument"))
    assertEquals(1, api.count("setChatDescription"))
  }

  @Test
  fun `remove persists changed index`() = LocalTelegram().use { api ->
    api.open().use { it.remove("key") }
    assertEquals(1, api.count("sendDocument"))
    assertEquals(1, api.count("setChatDescription"))
    api.assertUploadedIndex(emptyMap())
  }

  @Test
  fun `keys view removal persists changed index`() = LocalTelegram().use { api ->
    api.open().use { it.keys.remove("key") }
    assertEquals(1, api.count("sendDocument"))
    api.assertUploadedIndex(emptyMap())
  }

  @Test
  fun `set persists new file reference`() = LocalTelegram().use { api ->
    api.open().use { it["key"] = "replacement" }
    assertEquals(2, api.count("sendDocument"))
    assertEquals(1, api.count("setChatDescription"))
    api.assertUploadedIndex(mapOf("key" to "new-file"))
  }

  @Test
  fun `new storage still initializes its index`() = LocalTelegram(initial = null).use { api ->
    api.open().close()
    assertEquals(1, api.count("sendDocument"))
    assertEquals(1, api.count("setChatDescription"))
    api.assertUploadedIndex(emptyMap())
  }

  @Test
  fun `missing index still saves recovered empty index`() = LocalTelegram(missingIndex = true).use { api ->
    api.open().close()
    assertEquals(1, api.count("sendDocument"))
    api.assertUploadedIndex(emptyMap())
  }

  @Test
  fun `clear on empty persisted storage restores description on close`() = LocalTelegram(emptyMap()).use { api ->
    api.open().use { it.clear() }
    assertEquals(1, api.count("sendDocument"))
    assertEquals(2, api.count("setChatDescription"))
    api.assertUploadedIndex(emptyMap())
  }
}
