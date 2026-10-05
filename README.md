# Telegram Storage

This library is your `Map<K, V>` in the Telegram channel. To try it, your bot needs a channel (name or ID) with
full admin rights.

## Warnings

* You can save only 20 entries per minute with a single token. If the limit is exceeded, waiting will occur.
* Don't change the description—the bot stores the keystore file ID there
* After the first setup, you can't change the dictionary's key/value types

## Download

You need Gradle, Maven, or another build tool

[![](https://jitpack.io/v/demidko/telegram-storage.svg)](https://jitpack.io/#demidko/telegram-storage)

Also, you need to add [Kotlin serialization plugin](https://github.com/Kotlin/kotlinx.serialization), for example, in
_build.gradle.kts_

```kotlin
plugins {
    kotlin("plugin.serialization") version "2.1.20-Beta2"
}
```

## Usage example

```kotlin
import com.github.demidko.telegram.TelegramStorage.Constructors.TelegramStorage

@Serializable data class Person(val name: String, val address: String)

fun main() {
    val token = "Bot API token here"
    val channel = "Telegram channel name here" // or long id
    val storage = TelegramStorage<String, Person>(token, channel)

    // saved to Telegram channel
    storage["Special Government Employee"] = Person("Elon Musk", "Texas")

    // restored Person("Elon Musk", "Texas") from channel
    val p = storage["Special Government Employee"]!!
}
```

## Local tests and retention benchmark

`./gradlew test` runs loopback HTTP tests without Telegram credentials. Destructive live
integration tests require `TELEGRAM_STORAGE_LIVE_TESTS=true`, `BOT_TOKEN`, and `CHANNEL_NAME`;
use a dedicated disposable channel if opting in.

`./gradlew benchmarkClosedStorageRetention -Pentries=10000 -Pcycles=5` opens and closes
independent storage instances against a loopback fixture, requests GC, and reports how
many closed instances and index entries remain reachable. Each close still uploads its
index and waits for the channel description update. This measures object retention,
not live Telegram throughput; GC requests are best effort.

See [retention measurements and limitations](benchmarks/closed-storage-retention.md).
