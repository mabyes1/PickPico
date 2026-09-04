package com.mcpocket.poc;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import java.nio.ByteBuffer;

public final class RgbaPlaneBufferTest {
    @Test
    public void compactsRowsWhenFinalGpuRowOmitsPadding() {
        byte[] plane = new byte[] {
                1, 2, 3, 4, 5, 6, 7, 8, 99, 99, 99, 99,
                9, 10, 11, 12, 13, 14, 15, 16
        };

        ByteBuffer compact = RgbaPlaneBuffer.compact(ByteBuffer.wrap(plane), 2, 2, 4, 12);
        byte[] actual = new byte[compact.remaining()];
        compact.get(actual);

        assertArrayEquals(new byte[] {
                1, 2, 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15, 16
        }, actual);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTruncatedPixelData() {
        RgbaPlaneBuffer.compact(ByteBuffer.allocate(15), 2, 2, 4, 8);
    }
}
