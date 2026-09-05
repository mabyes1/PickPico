package com.mcpocket.poc;

import java.nio.ByteBuffer;

/** Converts a possibly row-padded RGBA image plane into tightly packed pixels. */
final class RgbaPlaneBuffer {
    private static final int BYTES_PER_PIXEL = 4;

    private RgbaPlaneBuffer() {
    }

    static ByteBuffer compact(
            ByteBuffer buffer,
            int width,
            int height,
            int pixelStride,
            int rowStride) {
        if (buffer == null || width <= 0 || height <= 0
                || pixelStride < BYTES_PER_PIXEL || rowStride < pixelStride * width) {
            throw new IllegalArgumentException("Unsupported RGBA plane layout");
        }

        int byteCount;
        try {
            byteCount = Math.multiplyExact(Math.multiplyExact(width, height), BYTES_PER_PIXEL);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("RGBA frame is too large", error);
        }

        ByteBuffer source = buffer.duplicate();
        int sourceStart = source.position();
        ByteBuffer compact = ByteBuffer.allocate(byteCount);
        for (int row = 0; row < height; row++) {
            int rowStart = sourceStart + row * rowStride;
            int lastPixelEnd = rowStart + (width - 1) * pixelStride + BYTES_PER_PIXEL;
            if (rowStart < sourceStart || lastPixelEnd < rowStart || lastPixelEnd > source.limit()) {
                throw new IllegalArgumentException("RGBA plane buffer ended early");
            }

            if (pixelStride == BYTES_PER_PIXEL) {
                ByteBuffer rowPixels = source.duplicate();
                rowPixels.position(rowStart);
                rowPixels.limit(rowStart + width * BYTES_PER_PIXEL);
                compact.put(rowPixels);
            } else {
                for (int column = 0; column < width; column++) {
                    int pixelStart = rowStart + column * pixelStride;
                    for (int channel = 0; channel < BYTES_PER_PIXEL; channel++) {
                        compact.put(source.get(pixelStart + channel));
                    }
                }
            }
        }
        compact.flip();
        return compact;
    }
}
