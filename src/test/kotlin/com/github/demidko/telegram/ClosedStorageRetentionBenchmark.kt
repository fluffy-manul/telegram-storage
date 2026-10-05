package com.github.demidko.telegram

import java.lang.ref.WeakReference
import java.lang.management.ManagementFactory

/** Loopback-only object-retention probe. GC requests are best effort, not a unit-test assertion. */
object ClosedStorageRetentionBenchmark {
  @JvmStatic
  fun main(args: Array<String>) {
    val entries = args.getOrNull(0)?.toInt() ?: 10000
    val cycles = args.getOrNull(1)?.toInt() ?: 5
    val references = (1..cycles).map { closeOne(entries) }
    repeat(5) {
      System.gc()
      Thread.sleep(100)
    }
    val retained = references.count { it.get() != null }
    val retainedEntries = references.sumOf { it.get()?.size ?: 0 }
    println("entries_per_storage=$entries cycles=$cycles retained_storages=$retained retained_entries=$retainedEntries")
    println("heap_used_bytes=${ManagementFactory.getMemoryMXBean().heapMemoryUsage.used}")
  }

  private fun closeOne(entries: Int): WeakReference<TelegramStorage<String, String>> =
    LocalTelegram((0 until entries).associate { "key-$it" to "file-$it" }).use { api ->
      val storage = api.open()
      val reference = WeakReference(storage)
      storage.close()
      check(api.count("sendDocument") == 1 && api.count("setChatDescription") == 1)
      reference
    }
}
