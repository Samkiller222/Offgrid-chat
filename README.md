# OffGrid Chat

Android messaging that works with no internet, no Wi-Fi and no phone signal. Phones talk
to each other directly over Bluetooth Low Energy, and every phone relays messages for
everyone else. No servers, no accounts, no Google Play services, no third party.

## How a message travels

1. You write to someone. The app signs it, encrypts it so only they can read it, and
   hands it to every phone in Bluetooth range (roughly 10 to 100 m).
2. Each of those phones keeps a copy and passes it to every phone *it* can reach.
3. **Store and carry.** When two phones meet, they swap lists of the messages they carry
   and fill in each other's gaps. So a message can sit in a stranger's pocket on a bus and
   jump forward when that phone meets the next one. A complete chain of phones never has
   to exist at one moment.
4. When the message reaches the recipient, their phone sends back a signed receipt that
   travels the same way. Phones that see the receipt delete their copy, and you see
   "delivered ✓✓".

Messages live for 3 days and up to 64 hops. This is the same idea as Bridgefy, Briar,
Bitchat and Apple's Find My network, minus the internet backbone Find My relies on.

**Range reality check:** Bluetooth alone only covers long distances if enough people
nearby run the app. For long range with few users, pair the phone with a LoRa radio (below).

## Long range: LoRa radios (experimental, untested on hardware)

Pair the phone with a cheap Meshtastic LoRa board and the app also sends messages over LoRa,
several km per hop. In the EU (incl. Malta) use the **868 MHz** version, e.g. Heltec
WiFi LoRa 32 V3 or Wireless Stick Lite V3.

1. Flash Meshtastic firmware on the board (flasher.meshtastic.org) and set region EU_868
   once with the Meshtastic app. Keep the default channel and LongFast preset if you want
   other people's Meshtastic nodes to relay for you. Then, in the Meshtastic app, disconnect
   from the board: it only accepts one phone connection at a time.
2. In OffGrid Chat tap **LoRa radio** and pick the board. Android asks to pair: boards with a
   screen show the PIN on it, boards without one use 123456 unless changed.

How it works: our signed packets travel as Meshtastic PRIVATE_APP broadcasts (split into
≤220-byte pieces, hop limit 3), so any Meshtastic node on the same channel relays them, up
to 3 hops beyond your own board. Airtime is scarce (a full piece is about 1 s on air on
LongFast, and the board's transmit queue is small), so only messages, receipts and your own
announcement go over LoRa, phones don't swap inventories over it, and the app waits for
free space in the board's queue. A phone with a radio bridges: what it hears over LoRa it
relays to nearby Bluetooth phones and vice versa.

## Privacy and security

- Your identity is a P-256 keypair generated on the phone. Your address is a hash of your
  public key, so nobody can claim it without your private key.
- Every packet is signed by its author. Relays can't forge or alter messages.
- Private messages are encrypted end to end (ECDH + AES-256-GCM). Relays see who a message
  is for, but not what it says.
- The "Everyone" room is public and not encrypted.
- Not yet handled: spam/flood protection, metadata privacy (relays can see sender and
  recipient ids), verifying a contact's key in person.

## Project layout

```
core/   Pure Kotlin mesh logic: packets, crypto, routing, store and carry. No Android code,
        unit tested on any JDK.
app/    Android app: Bluetooth transport, background service, plain UI.
tools/compile-check/   Type-checks app/ without the Android SDK (see below).
```

Key files:
- `core/.../MeshRouter.kt` routing, dedup, store and carry, receipts
- `core/.../Packet.kt` wire format
- `app/.../BleTransport.kt` BLE advertising, scanning, GATT links, chunking
- `app/.../MeshService.kt` foreground service that keeps relaying with the screen off

## Build and run

Open the folder in Android Studio and run on two or more real phones (Bluetooth doesn't
work in the emulator). Android 8.0 (API 26) or newer.

On first launch allow Bluetooth ("Nearby devices") and notifications. People show up in
the list once their announcement reaches you through the mesh; tap one to message them,
or use "Everyone" to talk to all phones in reach.

Run the mesh core tests on any machine with a JDK:

```
OFFGRID_CORE_ONLY=1 ./gradlew :core:test
```

Type-check the Android code without the SDK (uses a Robolectric android-all jar from Maven
Central):

```
ANDROID_ALL_JAR=/path/to/android-all-15-robolectric-13954326.jar ./gradlew -p tools/compile-check compileKotlin
```

## Known limits

- Bluetooth range is short; long range needs many users or the planned LoRa transport.
- Android caps simultaneous BLE connections (~5 to 7). Each phone dials at most 5 peers.
- Battery: the service scans and advertises in balanced mode. Not yet tuned.
- UI is intentionally plain for now.
