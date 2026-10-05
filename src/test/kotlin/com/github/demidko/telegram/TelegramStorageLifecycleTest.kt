package com.github.demidko.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.entities.ChatId
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

class TelegramStorageLifecycleTest {
  @Test
  fun `successful close unregisters its shutdown hook after publishing`() = StatefulTelegram().use { api ->
    val storage = api.open()
    val hookField = storage.javaClass.getDeclaredField("shutdownHook").apply { isAccessible = true }
    val hook = hookField.get(storage) as Thread
    storage.close()
    assertEquals(1, api.uploads.get())
    assertEquals(1, api.updates.get())
    assertFalse(Runtime.getRuntime().removeShutdownHook(hook), "close should have removed its registered hook")
    storage.close()
    assertEquals(1, api.uploads.get())
  }

  @Test
  fun `duplicate close racing the hook callback does not duplicate publication`() = StatefulTelegram().use { api ->
    val storage = api.open()
    val hookField = storage.javaClass.getDeclaredField("shutdownHook").apply { isAccessible = true }
    val hook = hookField.get(storage) as Thread
    val gate = CountDownLatch(1)
    api.descriptionGate = gate
    val callers = Executors.newFixedThreadPool(2)
    try {
      val hookClose = callers.submit { hook.run() }
      assertTrue(api.descriptionEntered.await(10, SECONDS))
      // Existing semantics: a duplicate close returns even while the winning call is publishing.
      callers.submit { storage.close() }.get(5, SECONDS)
      assertFalse(hookClose.isDone)
      gate.countDown()
      hookClose.get(10, SECONDS)
      assertEquals(1, api.uploads.get())
      assertEquals(1, api.updates.get())
      assertFalse(Runtime.getRuntime().removeShutdownHook(hook))
    } finally {
      gate.countDown()
      callers.shutdownNow()
    }
  }

  @Test
  fun `JVM shutdown still persists an unclosed storage without a hook removal error`() = StatefulTelegram().use { api ->
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val child = ProcessBuilder(
      java, "-cp", System.getProperty("test.runtime.classpath"),
      ShutdownPersistenceProbe::class.java.name, api.apiUrl
    ).redirectErrorStream(true).start()
    try {
      assertTrue(child.waitFor(20, SECONDS), "shutdown subprocess did not finish")
      val output = child.inputStream.bufferedReader().readText()
      assertEquals(0, child.exitValue(), output)
      assertFalse(output.contains("Exception"), output)
      assertEquals(1, api.uploads.get())
      assertEquals(1, api.updates.get())
      assertEquals(mapOf("key" to "value-file"), api.publishedIndex())
    } finally {
      if (child.isAlive) child.destroyForcibly()
    }
  }
}

/** Child JVM exits normally with an unclosed storage; all traffic goes to the parent's loopback fixture. */
object ShutdownPersistenceProbe {
  @JvmStatic
  fun main(args: Array<String>) {
    TelegramStorage(
      bot { token = "local"; apiUrl = args.single() },
      ChatId.fromId(1), String.serializer(), String.serializer()
    )
  }
}
