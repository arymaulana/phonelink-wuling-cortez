# PhoneLink protocol notes

Reverse engineered from `PhonelinkApk.apk` v2.94 (`libphonelinkRFB.so`, `libplagent`) and one working
session: Huawei LLD-L21, Android 9, Wuling Cortez headunit. Picture confirmed by the user. Touch not confirmed.

Labels: **[code]** read from decompiled code, **[log]** seen in the session log, **[guess]** inferred, not proven.

## USB link

- Phone is in USB accessory mode. Headunit identifies as manufacturer `LinkTech`, model `LinkServer`, version `1.0`. [log]
- Headunit is also an ADB host: its key is accepted from `adb_keys` on connect. [log]
- First connection dropped after about 2 seconds, then the whole sequence restarted and held. [log]
- No internet was needed in this session (`check_network_state disconnect`). [log]

## Frame format

```
FD  <cmd:1>  <len:4 big-endian>  <payload:len>  FE
```

Parser is a state machine in `unpack_data`. [code] Payload fields read directly as native ARM integers, so
multi-byte payload values are little-endian. [code]

## Headunit to phone

| cmd | Handler | Meaning |
|---|---|---|
| 0 | `check_protocol_version` | Protocol version, sent as `1,0` [log] |
| 1 | `get_client_soft_info` | 0x74-byte client info [code] |
| 2 | `reset_FIFO_buffer`, `get_client_disp_info` | Display info, 28 bytes [code] |
| 3 | `set_extract_data_type` | Meaning not mapped, not needed for screen start [code] |
| 4 | `get_key_info`, `is_home_key_up` | Key: `u16 id`, `u8 state`. Ids map to Android keycodes: 0 Home, 1 Back, 2 Menu, 8 Power. State 0 or 2 means released [code] |
| 5 | `get_touch_info` | Touch: `u16 x`, `u16 y`, `u8 type`, in headunit pixels. `type` bit 0 set means pressed (down or move), clear means up [code] |
| 6, 7 | none | Same empty target, ignored [code] |
| 8 | `get_client_cp_state` | Not used in the session [code] |
| 9 | `set_whole_screen`, `send_agent_quit` | Control, `u8` sub-value: 1 = screen start and whole-screen request (confirmed on Android 15), 3 = agent quit, 0/2/4 not mapped [code, log] |
| 10 | logs only | Not mapped |
| 11 | `get_client_BT_address` | Headunit Bluetooth address [code] |
| 12 | `check_protocol_version` | Version check, second variant [code] |
| 13 | `get_data_to_phone` | Pass-through data [code] |
| 14 | `get_client_external_fun1` | Not used in the session [code] |
| 15 | `get_client_request_auth` | Not used in the session [code] |

Values from the session: [log]

```
user_id:S1010000000000000000000000000000  app:1.0  version:002.201.2.7
client_id:2  platform_id:201  accessory:11  usb:11  sys:1  flag:0
uHH=600, uHW=1024, uVH=600, uVW=300, decode=0
```

## Phone to headunit

| cmd | Total bytes | Payload |
|---|---|---|
| 0x80 | 9 | 2 bytes: ok flag (1 when the argument is non-zero), 0 [code, log] |
| 0x81 | 11 | 4 bytes: 0, 0, then 2 bytes from a stored value (likely app version) [code, log] |
| 0x82 | 19 | 12 bytes: `u16 width`, `u16 height & ~3`, 2 more `u16`, then 0 [code, log] |
| 0x83 | 21 + 2n | Screen data, below [code] |
| 0x86, 0x87, 0x88 | varies | Auth result, license, screen on/off (`0x88` is 8 bytes) [code] |

### Screen packet (0x83)

```
offset  size
0       1    FD
1       1    83
2       4    len = 2n + 14 (big-endian)
6       2    x
8       2    y
10      2    w
12      2    h
14      1    flag: 0 or 1, chosen by the current mode, likely landscape/portrait [guess]
15      3    other bytes of the 12-byte header; byte 15 is set to 0, 16-17 unmapped
18      2    gap, contents not mapped
20      2n   pixels, n = w * h, RGB565, 2 bytes each
20+2n   1    FE
```

- No compression: `libplagent` links zlib but imports no deflate or compress calls. [code]
- Dirty rectangle only, except when a whole screen is requested. [code]
- Capture converts RGBA8888 to RGB565 with `(c & 0xf8) << 8 | (c & 0xfc00) >> 5 | (c & 0xf80000) >> 19`. [code]
- A full 1024x600 frame is about 1.2 MB. [derived]

## Old app internals (not needed in a rewrite)

- Native agent `libplagent` shares `files/PLbuf` (3,932,160 bytes) and `files/PLinfobuf` (4,096 bytes) with the app. [code, log]
- Touch input goes through `monkey --port 13137`, `input keyevent`, or `/dev/input`, depending on the phone. [code]
- Capture uses `ScreenshotClient` through `libPLmf<api>.so`. Newest is API 28. [code]
- Picture is stretched to the headunit size, shape not kept: `FUN_00011c70` in `libplagent` (logged as `get_pixel_index`) builds a nearest-pixel table using separate scales, `srcW / HW` and `srcH / HH`, for each of 4 rotations. Touch mapping (`FUN_00013334`, `FUN_000133d8`) also scales x and y separately. [code]

## Session order [log]

1. USB connects, phone accepts the ADB key, `dumpsys` runs.
2. App starts and writes `libplagent` and the marker `agentontmp` into its files.
3. Accessory mode comes up and the app opens it.
4. Agent starts, about 1 second before the handshake.
5. Commands 0, 1 and 2 arrive, with replies 0x80, 0x81 and 0x82.
6. Control command arrives (`RECEIVE_RET_SCREEN_START`), whole screen is requested, frames flow.

## Confirmed on Android 15 (TECNO LJ7, first version of `app`)

Color bars showed on the headunit in the right order, with the moving block. About 90 seconds, no errors.

Exact headunit ADB commands, from `adbd service requested` log lines: [log]

```
pm list packages -3
dumpsys package com.phonelink.phonelinkserver
am start -n com.phonelink.phonelinkserver/.MainActivity
ls -l /data/data/com.phonelink.phonelinkserver/files
(USB switches to accessory mode)
pm list packages -3
dumpsys package com.phonelink.phonelinkserver
ls -l /data/data/com.phonelink.phonelinkserver/files
data/data/com.phonelink.phonelinkserver/files/libplagent
```

- **The agent is not needed.** Shell can't read the app folder on Android 15, so the agent never ran, and mirroring worked anyway. [log]
- No shell helper is possible this way, so capture must run inside the app (`MediaProjection`).
- Reply `0x81` with four zero bytes was accepted. [log]
- RGB565 little-endian colors are correct. [observed by user]
- Handshake took about 8 ms: cmds 0, 0, 1, 2, then 9 with value 1. [log]
- A full 1024x600 frame took 43 to 87 ms to write, median 49 ms, so about 20 full frames per second at most. [log]
- No drop and reconnect on this phone, unlike the Huawei. [log]
- Real mirroring ran 146 seconds at about 16 frames per second with dirty rectangles. [log]
- Touch arrives as cmd 5, about one message every 50 ms while pressed, with a final `type=0` at release. [log]
- No cmd 4 key messages were seen in that session. [log]

## Reconnect behavior [log]

- After USB drops, Android keeps listing the accessory for about a second. Opening it in that window gives
  a dead connection that holds `/dev/usb_accessory` exclusively. The next plug-in then logs
  `could not open /dev/usb_accessory` and never enters accessory mode.
- The headunit retries its ADB commands about every 7 seconds and reports USB debugging as off, even though ADB works.

## Still unknown

- Whether the headunit installs its own app with `pm install` on a phone without the app.
- Whether anything still works if USB debugging is off.
- Meaning of cmd 3, cmd 9 sub-values 0, 2 and 4, and cmd 10.
