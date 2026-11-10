package com.phonelink.phonelinkserver;

import java.util.ArrayList;
import java.util.List;

/** PhoneLink USB messages and pure pixel/touch math. See docs/phonelink-protocol.md. No Android imports, so it runs on a PC too. */
final class Protocol {
    static final int CMD_VERSION = 0;
    static final int CMD_SOFT_INFO = 1;
    static final int CMD_DISP_INFO = 2;
    static final int CMD_KEY = 4;
    static final int CMD_TOUCH = 5;
    static final int CMD_CONTROL = 9;
    static final int CONTROL_SCREEN_START = 1;

    private static final int START = 0xFD;
    private static final int END = 0xFE;
    private static final int MAX_PAYLOAD = 64 * 1024; // headunit messages seen are under 200 bytes
    private static final int MERGE_GAP_ROWS = 32;

    private Protocol() {}

    interface Handler {
        void onMessage(int cmd, byte[] payload);
    }

    /** Same state machine as the old app's unpack_data. Bad frames are dropped, then it resyncs on FD. */
    static final class Parser {
        private static final int WAIT_START = 0, CMD = 1, LENGTH = 2, PAYLOAD = 3, WAIT_END = 4;
        private final Handler handler;
        private int state = WAIT_START;
        private int cmd, length, read;
        private byte[] payload;

        Parser(Handler handler) {
            this.handler = handler;
        }

        void feed(byte[] data, int count) {
            for (int i = 0; i < count; i++) {
                int b = data[i] & 0xFF;
                switch (state) {
                    case WAIT_START:
                        if (b == START) state = CMD;
                        break;
                    case CMD:
                        cmd = b;
                        length = 0;
                        read = 0;
                        state = LENGTH;
                        break;
                    case LENGTH:
                        length = (length << 8) | b;
                        if (++read == 4) {
                            read = 0;
                            if (length < 0 || length > MAX_PAYLOAD) {
                                state = WAIT_START;
                            } else {
                                payload = new byte[length];
                                state = length == 0 ? WAIT_END : PAYLOAD;
                            }
                        }
                        break;
                    case PAYLOAD:
                        payload[read++] = (byte) b;
                        if (read == length) state = WAIT_END;
                        break;
                    default: // WAIT_END
                        state = WAIT_START;
                        if (b == END) handler.onMessage(cmd, payload);
                        break;
                }
            }
        }
    }

    static byte[] packet(int cmd, byte[] payload) {
        byte[] out = new byte[payload.length + 7];
        out[0] = (byte) START;
        out[1] = (byte) cmd;
        out[2] = (byte) (payload.length >>> 24);
        out[3] = (byte) (payload.length >>> 16);
        out[4] = (byte) (payload.length >>> 8);
        out[5] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 6, payload.length);
        out[out.length - 1] = (byte) END;
        return out;
    }

    static byte[] versionReply(boolean ok) {
        return packet(0x80, new byte[] {(byte) (ok ? 1 : 0), 0});
    }

    static byte[] softInfoReply() {
        return packet(0x81, new byte[4]);
    }

    static byte[] dispReply(int hWidth, int hHeight, int vWidth, int vHeight) {
        byte[] p = new byte[12];
        putLe16(p, 0, hWidth);
        putLe16(p, 2, hHeight & ~3);
        putLe16(p, 4, vWidth);
        putLe16(p, 6, vHeight);
        return packet(0x82, p);
    }

    /** Portrait width the old agent reports: height scaled by the phone's aspect, rounded down to 4. */
    static int portraitWidth(int vWidth, int vHeight, int phoneShort, int phoneLong) {
        return Math.min(vWidth, (vHeight * phoneShort / phoneLong) & ~3);
    }

    /** Android's colon-separated accessibility service list with one service added or removed. Keeps all others. */
    static String withService(String list, String fullName, String shortName, boolean on) {
        List<String> kept = new ArrayList<>();
        if (list != null) {
            for (String s : list.split(":")) {
                if (!s.isEmpty() && !s.equals(fullName) && !s.equals(shortName)) kept.add(s);
            }
        }
        if (on) kept.add(fullName);
        return String.join(":", kept);
    }

    /** RGB565 little-endian pixels, as seen in a real frame from the old app. */
    static byte[] frame(int x, int y, int w, int h, byte[] pixels) {
        byte[] p = new byte[14 + pixels.length];
        putLe16(p, 0, x);
        putLe16(p, 2, y);
        putLe16(p, 4, w);
        putLe16(p, 6, h);
        System.arraycopy(pixels, 0, p, 14, pixels.length);
        return packet(0x83, p);
    }

    /** Sent by the old app when nothing on screen changed. */
    static byte[] emptyFrame() {
        return frame(0, 0, 0, 0, new byte[0]);
    }

    /** Converts an area of RGBA_8888 pixels (R in the low byte, as read little-endian) to wire RGB565. */
    static byte[] toRgb565(int[] px, int stride, int x, int y, int w, int h) {
        byte[] out = new byte[w * h * 2];
        int o = 0;
        for (int row = y; row < y + h; row++) {
            int i = row * stride + x;
            for (int end = i + w; i < end; i++) {
                int c = px[i];
                int v = (c & 0xF8) << 8 | (c & 0xFC00) >> 5 | (c & 0xF80000) >> 19;
                out[o++] = (byte) v;
                out[o++] = (byte) (v >> 8);
            }
        }
        return out;
    }

    /**
     * Boxes {x, y, w, h} around changed pixels, empty if none. Changes more than MERGE_GAP_ROWS apart get
     * separate boxes. Aligned to x/w multiples of 4 and y/h multiples of 2, like frames from the old app.
     * ponytail: splits by rows only, so two changes side by side share a box. Good enough for status bars
     * and lists; split by columns too if that ever shows up as lag.
     */
    static List<int[]> dirtyRects(int[] cur, int[] prev, int stride, int w, int h) {
        List<int[]> rects = new ArrayList<>();
        int top = -1, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            int base = y * stride;
            int first = -1, last = -1;
            for (int x = 0; x < w; x++) {
                if (cur[base + x] != prev[base + x]) {
                    if (first < 0) first = x;
                    last = x;
                }
            }
            if (first < 0) continue;
            if (top >= 0 && y - bottom > MERGE_GAP_ROWS) {
                rects.add(aligned(left, top, right, bottom, w, h));
                top = -1;
                left = w;
                right = -1;
            }
            if (top < 0) top = y;
            bottom = y;
            left = Math.min(left, first);
            right = Math.max(right, last);
        }
        if (top >= 0) rects.add(aligned(left, top, right, bottom, w, h));
        return rects;
    }

    private static int[] aligned(int left, int top, int right, int bottom, int w, int h) {
        int x = left & ~3;
        int y = top & ~1;
        return new int[] {x, y, Math.min(w, (right + 4) & ~3) - x, Math.min(h, (bottom + 2) & ~1) - y};
    }

    /**
     * Headunit touch point to phone screen pixels. Android letterboxes the phone inside the mirrored
     * display, keeping its aspect, so the same math undoes it. Result is clamped to the screen.
     */
    static int[] toPhone(int x, int y, int unitW, int unitH, int phoneW, int phoneH) {
        double scale = Math.min((double) unitW / phoneW, (double) unitH / phoneH);
        double left = (unitW - phoneW * scale) / 2;
        double top = (unitH - phoneH * scale) / 2;
        int px = (int) Math.round((x - left) / scale);
        int py = (int) Math.round((y - top) / scale);
        return new int[] {Math.max(0, Math.min(phoneW - 1, px)), Math.max(0, Math.min(phoneH - 1, py))};
    }

    /**
     * Capture height matching the phone's shape at the headunit's width, so Android adds no bands.
     * A phone no wider than the headunit stays at full height and gets no stretch.
     */
    static int captureHeight(int unitW, int unitH, int phoneW, int phoneH) {
        return Math.min(unitH, unitW * Math.min(phoneW, phoneH) / Math.max(phoneW, phoneH));
    }

    /** Height of the stretched picture on the headunit. Even, so the bands above and below match. */
    static int pictureHeight(int captureH, int unitH, int stretchPercent) {
        return Math.min(unitH, captureH * stretchPercent / 100) & ~1;
    }

    /** Repeats rows into dst from row top, the same nearest-pixel stretch the old app used. Bands stay as they are. */
    static void stretchRows(int[] src, int stride, int srcH, int[] dst, int top, int pictureH) {
        // ponytail: repeated rows make text slightly rough, GPU scaling if that matters
        for (int y = 0; y < pictureH; y++) {
            System.arraycopy(src, (y * srcH / pictureH) * stride, dst, (top + y) * stride, stride);
        }
    }

    static int le16(byte[] b, int offset) {
        return (b[offset] & 0xFF) | (b[offset + 1] & 0xFF) << 8;
    }

    private static void putLe16(byte[] b, int offset, int value) {
        b[offset] = (byte) value;
        b[offset + 1] = (byte) (value >> 8);
    }

    static String hex(byte[] b, int max) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length && i < max; i++) {
            s.append(String.format("%02x ", b[i]));
        }
        if (b.length > max) s.append("...(").append(b.length).append(")");
        return s.toString().trim();
    }
}
