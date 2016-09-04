package com.hippo.glview.image;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;

/**
 * To work on unit tests, switch the Test Artifact in the Build Variants view.
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() throws Exception {
        assertEquals(4, 2 + 2);
    }

    @Test
    public void layoutTiles_isCorrect() throws Exception {
        for (int i = 10; i < TILE_CONTENT_SIZE[TILE_LARGEST] * 3; i += 10) {
            for (int j = 10; j < TILE_CONTENT_SIZE[TILE_LARGEST] * 3; j += 10) {
                layoutTiles_isCorrect(i, j);
            }
        }
    }

    private static final int TILE_SMALLEST = 0;
    private static final int TILE_LARGEST = 2;

    private static final int[] TILE_CONTENT_SIZE = {254, 508, 1016};

    private void layoutTiles_isCorrect(int width, int height) {
        ArrayList<Rect> list = new ArrayList<>();
        layoutTiles(list, width, height, 0, 0, TILE_LARGEST);

        int tilesArea = 0;
        for (Rect rect : list) {
            tilesArea += rect.width() * rect.height();
        }
        Assert.assertEquals(width * height, tilesArea);
    }

    private void layoutTiles(ArrayList<Rect> list, int width, int height, int offsetX, int offsetY, int tileSize) {
        final int tileContentSize = TILE_CONTENT_SIZE[tileSize];
        final int nextTileContentSize = tileSize == TILE_SMALLEST ? 0 : TILE_CONTENT_SIZE[tileSize - 1];
        int remainWidth, remainHeight;
        int lineWidth, lineHeight;
        int lineOffsetX, lineOffsetY;

        for (lineOffsetY = offsetY, remainHeight = height - lineOffsetY;
             remainHeight > 0;
             lineOffsetY += tileContentSize, remainHeight = height - lineOffsetY) {
            // Check whether current tile size is too large
            if (remainHeight <= nextTileContentSize) {
                layoutTiles(list, width, height, offsetX, lineOffsetY, tileSize - 1);
                // It is the last line for current tile, break now
                break;
            }
            lineHeight = Math.min(tileContentSize, remainHeight);

            for (lineOffsetX = offsetX, remainWidth = width - lineOffsetX;
                 remainWidth > 0;
                 lineOffsetX += tileContentSize, remainWidth = width - lineOffsetX) {
                // Check whether current tile size is too large
                if (remainWidth <= nextTileContentSize) {
                    // Only layout for this line, so height = offsetY + lineHeight
                    layoutTiles(list, width, lineOffsetY + lineHeight, lineOffsetX, lineOffsetY, tileSize - 1);
                    // It is the end of this line for current tile, break now
                    break;
                }
                lineWidth = Math.min(tileContentSize, remainWidth);
                list.add(new Rect(lineOffsetX, lineOffsetY, lineOffsetX + lineWidth, lineOffsetY + lineHeight));
            }
        }
    }

    public final class Rect {
        public int left;
        public int top;
        public int right;
        public int bottom;

        public Rect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public final int width() {
            return right - left;
        }

        public final int height() {
            return bottom - top;
        }
    }
}