package com.phonelink.phonelinkserver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Self-check on a PC: expected bytes come from real frames in the Huawei's PLbuf. Run by build.cmd. */
public class ProtocolCheck {
    private static void eq(String name, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + "\n expected " + expected + "\n actual   " + actual);
        }
    }

    private static String boxes(int[] cur, int[] prev) {
        return Arrays.deepToString(Protocol.dirtyRects(cur, prev, 100, 100, 100).toArray());
    }

    public static void main(String[] args) {
        // Real empty frame, 21 bytes.
        eq("empty frame", "fd 83 00 00 00 0e 00 00 00 00 00 00 00 00 00 00 00 00 00 00 fe",
                Protocol.hex(Protocol.emptyFrame(), 99));

        // Real 36x20 frame at (108,10), 1461 bytes.
        byte[] real = Protocol.frame(108, 10, 36, 20, new byte[36 * 20 * 2]);
        eq("real frame head", "fd 83 00 00 05 ae 6c 00 0a 00 24 00 14 00 00 00 00 00 00 00",
                Protocol.hex(Arrays.copyOf(real, 20), 99));
        if (real.length != 1461 || (real[1460] & 0xFF) != 0xFE) throw new AssertionError("real frame size or end");

        // Sizes from the session log: 9, 11, 19. Display values logged as 1024, 600, 300, 600.
        eq("version", "fd 80 00 00 00 02 01 00 fe", Protocol.hex(Protocol.versionReply(true), 99));
        eq("soft info", "fd 81 00 00 00 04 00 00 00 00 fe", Protocol.hex(Protocol.softInfoReply(), 99));
        eq("disp", "fd 82 00 00 00 0c 00 04 58 02 2c 01 58 02 00 00 00 00 fe",
                Protocol.hex(Protocol.dispReply(1024, 600, 300, 600), 99));
        if (Protocol.portraitWidth(1024, 600, 1080, 2160) != 300) throw new AssertionError("portrait width");

        // Parser: garbage, split reads, bad end byte, oversized length, then a good message.
        List<String> got = new ArrayList<>();
        Protocol.Parser parser = new Protocol.Parser((cmd, p) -> got.add(cmd + ":" + Protocol.hex(p, 99)));
        byte[] stream = {
            0x11, 0x22,
            (byte) 0xFD, 0, 0, 0, 0, 2, 1, 0, (byte) 0xFE,
            (byte) 0xFD, 5, 0, 0, 0, 1, 7, 0x00,
            (byte) 0xFD, 5, 0x7F, 0, 0, 0,
            (byte) 0xFD, 5, 0, 0, 0, 5, 0x10, 0x01, 0x20, 0x02, 1, (byte) 0xFE
        };
        for (byte b : stream) parser.feed(new byte[] {b}, 1);
        eq("parser", "[0:01 00, 5:10 01 20 02 01]", got.toString());

        // RGBA (R in low byte) to wire RGB565. Colors match what the headunit showed correctly as bars.
        int red = 0xFF0000FF, green = 0xFF00FF00, blue = 0xFFFF0000;
        eq("red green blue", "00 f8 e0 07 1f 00",
                Protocol.hex(Protocol.toRgb565(new int[] {red, green, blue}, 3, 0, 0, 3, 1), 9));

        // Row stride wider than the image, and an inner area.
        int[] px = {0, 0, 0, 0, 0, 0, 0, red, 0, 0};
        eq("stride", "00 f8", Protocol.hex(Protocol.toRgb565(px, 5, 2, 1, 1, 1), 9));

        // Dirty boxes: no change, then one pixel, aligned to 4 across and 2 down.
        int[] a = new int[100 * 100];
        int[] b = new int[100 * 100];
        eq("no change", "[]", boxes(a, b));
        a[5 * 100 + 13] = 1;
        eq("dirty one", "[[12, 4, 4, 2]]", boxes(a, b));
        // Close together: merged, and the right edge stays in bounds.
        a[9 * 100 + 99] = 1;
        eq("merged", "[[12, 4, 88, 6]]", boxes(a, b));
        // Far apart: separate boxes, so the rows between aren't resent.
        a[99 * 100 + 40] = 1;
        eq("split", "[[12, 4, 88, 6], [40, 98, 4, 2]]", boxes(a, b));
        // Exactly the merge gap apart stays merged; one more row splits.
        int[] c = new int[100 * 100];
        c[0] = 1;
        c[32 * 100] = 1;
        eq("gap merged", "[[0, 0, 4, 34]]", boxes(c, b));
        int[] e = new int[100 * 100];
        e[0] = 1;
        e[33 * 100] = 1;
        eq("gap split", "[[0, 0, 4, 2], [0, 32, 4, 2]]", boxes(e, b));

        // Touch mapping, landscape phone 2400x1080 on 1024x600: bands above and below. Scale is 1024/2400.
        eq("landscape center", "[1200, 540]", Arrays.toString(Protocol.toPhone(512, 300, 1024, 600, 2400, 1080)));
        // Band height is (600 - 1080 * 1024 / 2400) / 2 = 69.6, so y=70 is just inside, on row 1.
        eq("landscape corner", "[0, 1]", Arrays.toString(Protocol.toPhone(0, 70, 1024, 600, 2400, 1080)));
        // Portrait phone 1080x2400: bands left and right, touches outside clamp to the edge.
        eq("portrait center", "[540, 1200]", Arrays.toString(Protocol.toPhone(512, 300, 1024, 600, 1080, 2400)));
        eq("portrait outside", "[0, 0]", Arrays.toString(Protocol.toPhone(0, 0, 1024, 600, 1080, 2400)));

        // Stretch: capture height for the TECNO, the Huawei, either orientation, and a phone needing no stretch.
        if (Protocol.captureHeight(1024, 600, 2400, 1080) != 460) throw new AssertionError("capture TECNO");
        if (Protocol.captureHeight(1024, 600, 1080, 2400) != 460) throw new AssertionError("capture portrait");
        if (Protocol.captureHeight(1024, 600, 2160, 1080) != 512) throw new AssertionError("capture Huawei");
        if (Protocol.captureHeight(1024, 600, 1200, 900) != 600) throw new AssertionError("capture no stretch");

        // Picture height on the real TECNO (capture 449): 17% stretch, full fill clamps, and no stretch.
        if (Protocol.captureHeight(1024, 600, 2460, 1080) != 449) throw new AssertionError("capture real TECNO");
        if (Protocol.pictureHeight(449, 600, 117) != 524) throw new AssertionError("picture 17%");
        if (Protocol.pictureHeight(449, 600, 200) != 600) throw new AssertionError("picture clamp");
        if (Protocol.pictureHeight(449, 600, 100) != 448) throw new AssertionError("picture no stretch");

        // Rows repeat in order, first and last rows both survive, and bands stay black.
        int[] stretched = new int[14];
        Protocol.stretchRows(new int[] {1, 11, 2, 22, 3, 33}, 2, 3, stretched, 1, 5);
        eq("stretch rows", "[0, 0, 1, 11, 1, 11, 2, 22, 2, 22, 3, 33, 0, 0]", Arrays.toString(stretched));

        // Touch on the 524-row picture at row 38, the same conversion as MirrorService.onTouch.
        eq("stretch center", "[1230, 539]", Arrays.toString(Protocol.toPhone(512, (300 - 38) * 449 / 524, 1024, 449, 2460, 1080)));
        eq("stretch top band", "[1230, 0]", Arrays.toString(Protocol.toPhone(512, (0 - 38) * 449 / 524, 1024, 449, 2460, 1080)));
        eq("stretch bottom edge", "[1230, 1078]", Arrays.toString(Protocol.toPhone(512, (561 - 38) * 449 / 524, 1024, 449, 2460, 1080)));
        eq("stretch bottom band", "[1230, 1079]", Arrays.toString(Protocol.toPhone(512, (599 - 38) * 449 / 524, 1024, 449, 2460, 1080)));

        // Accessibility list: other apps' services survive, and both spellings of ours are recognized.
        String me = "p/p.T", brief = "p/.T", other = "x/x.S";
        eq("service on, empty", me, Protocol.withService(null, me, brief, true));
        eq("service on, kept", other + ":" + me, Protocol.withService(other, me, brief, true));
        eq("service on, no double", other + ":" + me, Protocol.withService(me + ":" + other, me, brief, true));
        eq("service off", other, Protocol.withService(other + ":" + me, me, brief, false));
        eq("service off, short name", other, Protocol.withService(brief + ":" + other, me, brief, false));
        eq("service off, last one", "", Protocol.withService(me, me, brief, false));
        eq("service off, none set", "", Protocol.withService(null, me, brief, false));

        System.out.println("PROTOCOL CHECK OK");
    }
}
