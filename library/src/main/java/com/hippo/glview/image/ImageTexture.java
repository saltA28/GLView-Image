/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.glview.image;

import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.os.Process;
import android.os.SystemClock;
import android.support.annotation.NonNull;

import com.hippo.glview.annotation.RenderThread;
import com.hippo.glview.glrenderer.GLCanvas;
import com.hippo.glview.glrenderer.NativeTexture;
import com.hippo.glview.glrenderer.Texture;
import com.hippo.glview.view.GLRoot;
import com.hippo.image.ImageData;
import com.hippo.image.ImageRenderer;
import com.hippo.yorozuya.thread.InfiniteThreadExecutor;
import com.hippo.yorozuya.thread.PriorityThreadFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class ImageTexture implements Texture, Animatable {

    private static final int TILE_SMALLEST = 0;
    private static final int TILE_LARGEST = 2;

    private static final int[] TILE_CONTENT_SIZE = {254, 508, 1016};
    private static final int[] TILE_BORDER_SIZE = {1, 2, 4};
    private static final int[] TILE_WHOLE_SIZE = {256, 512, 1024};
    private static final Tile[] TILE_FREE_HEAD = {null, null, null};

    public static final int LARGEST_TILE_SIZE = TILE_WHOLE_SIZE[TILE_LARGEST];

    private static final Object sFreeTileLock = new Object();

    private static final int INIT_CAPACITY = 8;

    // We are targeting at 60fps, so we have 16ms for each frame.
    // In this 16ms, we use about 4~8 ms to upload tiles.
    private static final long UPLOAD_TILE_LIMIT = 4; // ms

    private static final Executor sThreadExecutor =
            new InfiniteThreadExecutor(10 * 1000, new LinkedList<Runnable>(),
                    new PriorityThreadFactory("ImageTexture$AnimateTask",
                            Process.THREAD_PRIORITY_BACKGROUND));
    private static final PVLock sPVLock = new PVLock(3);

    private final ImageRenderer mImage;
    private int mUploadIndex = 0;
    private final Tile[] mTiles;  // Can be modified in different threads.
                                  // Should be protected by "synchronized."

    private final int mWidth;
    private final int mHeight;
    private final boolean mOpaque;
    private final RectF mSrcRect = new RectF();
    private final RectF mDestRect = new RectF();

    private boolean mAnimating;
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final AtomicBoolean mReset = new AtomicBoolean();
    private Runnable mAnimateRunnable = null;

    private final Lock mLock = new Lock();
    private boolean mNeedRecycle;

    private final AtomicBoolean mFrameDirty = new AtomicBoolean();

    private WeakReference<Callback> mCallback;

    private static class Lock {

        private boolean mLocked;

        public synchronized boolean isLocked() {
            return mLocked;
        }

        public synchronized <V> V lock(Callable<V> c) {
            if (mLocked) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    throw new IllegalStateException("Can't interrupt this thread", e);
                }
            }
            mLocked = true;
            try {
                return c.call();
            } catch (Exception e) {
                throw new IllegalStateException("No Exception allowed", e);
            }
        }

        public synchronized <V> V unlock(Callable<V> c) {
            if (!mLocked) {
                throw new IllegalStateException("The lock is not locked");
            }
            mLocked = false;
            this.notify();
            try {
                return c.call();
            } catch (Exception e) {
                throw new IllegalStateException("No Exception allowed", e);
            }
        }
    }

    private static class PVLock {

        private int mCounter;

        public PVLock(int count) {
            mCounter = count;
        }

        public synchronized void p() {
            while (true) {
                if (mCounter > 0) {
                    mCounter--;
                    break;
                } else {
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException("Can't interrupt this thread", e);
                    }
                }
            }
        }

        public synchronized void v() {
            mCounter++;
            if (mCounter > 0) {
                this.notify();
            }
        }
    }

    public static class Uploader implements GLRoot.OnGLIdleListener {
        private final ArrayDeque<ImageTexture> mTextures =
                new ArrayDeque<>(INIT_CAPACITY);

        private final GLRoot mGlRoot;
        private boolean mIsQueued = false;

        public Uploader(GLRoot glRoot) {
            mGlRoot = glRoot;
        }

        public synchronized void clear() {
            mTextures.clear();
        }

        public synchronized void addTexture(ImageTexture t) {
            if (t.isReady()) return;
            mTextures.addLast(t);

            if (mIsQueued) return;
            mIsQueued = true;
            mGlRoot.addOnGLIdleListener(this);
        }

        @Override
        public boolean onGLIdle(GLCanvas canvas, boolean renderRequested) {
            final ArrayDeque<ImageTexture> deque = mTextures;
            synchronized (this) {
                long now = SystemClock.uptimeMillis();
                final long dueTime = now + UPLOAD_TILE_LIMIT;
                while (now < dueTime && !deque.isEmpty()) {
                    final ImageTexture t = deque.peekFirst();
                    if (t.uploadNextTile(canvas)) {
                        deque.removeFirst();
                        mGlRoot.requestRender();
                    }
                    now = SystemClock.uptimeMillis();
                }
                mIsQueued = !mTextures.isEmpty();

                // return true to keep this listener in the queue
                return mIsQueued;
            }
        }
    }

    private static class Tile extends NativeTexture {

        private int tileSize;
        private int borderSize;
        // Width of the area in image which this tile represent for
        private int width;
        // Height of the area in image which this tile represent for
        private int height;
        // Offset x of the area in image which this tile represent for
        public int offsetX;
        // Offset y of the area in image which this tile represent for
        public int offsetY;
        public ImageRenderer image;
        public Tile nextFreeTile;

        public void setSize(int tileSize, int width, int height, int offsetX, int offsetY) {
            this.tileSize = tileSize;
            this.width = width;
            this.height = height;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            borderSize = TILE_BORDER_SIZE[tileSize];
            mWidth = width + 2 * borderSize;
            mHeight = height + 2 * borderSize;
            mTextureWidth = TILE_WHOLE_SIZE[tileSize];
            mTextureHeight = TILE_WHOLE_SIZE[tileSize];
        }

        @Override
        protected void texImage(boolean init) {
            if (image != null && !image.isRecycled()) {
                final int w, h;
                if (init) {
                    w = mTextureWidth;
                    h = mTextureHeight;
                } else {
                    w = mWidth;
                    h = mHeight;
                }
                image.glTex(init, w, h, 0, 0, offsetX - borderSize, offsetY - borderSize, w, h, 1);
            }
        }

        private void invalidate() {
            invalidateContent();
            image = null;
        }

        public void free() {
            invalidate();
            synchronized (sFreeTileLock) {
                nextFreeTile = TILE_FREE_HEAD[tileSize];
                TILE_FREE_HEAD[tileSize] = this;
            }
        }
    }

    private static Tile obtainTile(int tileSize) {
        synchronized (sFreeTileLock) {
            final Tile result = TILE_FREE_HEAD[tileSize];
            if (result == null) {
                return new Tile();
            } else {
                TILE_FREE_HEAD[tileSize] = result.nextFreeTile;
                result.nextFreeTile = null;
            }
            return result;
        }
    }

    private class AnimateRunnable implements Runnable {

        private final Callable<Boolean> mTryRecycle = new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                if (mImage.isRecycled()) {
                    return true;
                } else if (mNeedRecycle) {
                    mImage.recycle();
                    final ImageData imageData = mImage.getImageData();
                    if (!imageData.isReferenced()) imageData.recycle();
                    return true;
                } else {
                    return false;
                }
            }
        };

        @Override
        public void run() {
            final ImageData imageData = mImage.getImageData();
            long lastTime = System.nanoTime();
            long lastDelay = -1L;
            boolean recycled = false;

            if (!imageData.isCompleted()) {
                sPVLock.p();

                recycled = mLock.lock(mTryRecycle);
                if (!recycled) {
                    synchronized (imageData) {
                        imageData.complete();
                    }
                }
                recycled = mLock.unlock(mTryRecycle);

                sPVLock.v();
            }

            if (recycled || imageData.getFrameCount() == 1) {
                mAnimateRunnable = null;
                return;
            }

            for (;;) {
                synchronized (mLock) {
                    if (!mAnimating) {
                        mAnimateRunnable = null;
                        mRunning.lazySet(false);
                        return;
                    }
                }
                mRunning.lazySet(true);

                recycled = mLock.lock(mTryRecycle);
                if (!recycled) {
                    if (mReset.getAndSet(false)) {
                        mImage.reset();
                    } else {
                        mImage.advance();
                    }
                }
                recycled = mLock.unlock(mTryRecycle);

                if (recycled) {
                    mAnimateRunnable = null;
                    mRunning.lazySet(false);
                    return;
                }

                // Get delay
                long delay = mImage.getCurrentDelay();
                final long now = System.nanoTime();
                // Fix delay
                if (-1L != lastDelay) {
                    delay -= (now - lastTime) / 1000000 - lastDelay;
                }
                lastTime = now;
                lastDelay = delay;
                mFrameDirty.lazySet(true);
                invalidateSelf();

                // Delay
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
            }
        }
    }

    public ImageTexture(@NonNull ImageData image) {
        mImage = image.createImageRenderer();
        mWidth = image.getWidth();
        mHeight = image.getHeight();
        mOpaque = image.isOpaque();
        final ArrayList<Tile> list = new ArrayList<>();
        layoutTiles(list, mImage, mOpaque, mWidth, mHeight, 0, 0, TILE_LARGEST);
        mTiles = list.toArray(new Tile[list.size()]);

        if (!image.isCompleted()) {
            mAnimateRunnable = new AnimateRunnable();
            sThreadExecutor.execute(mAnimateRunnable);
        }
    }

    private void layoutTiles(ArrayList<Tile> list, ImageRenderer image, boolean opaque,
            int width, int height, int offsetX, int offsetY, int tileSize) {
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
                layoutTiles(list, image, opaque, width, height, offsetX, lineOffsetY, tileSize - 1);
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
                    layoutTiles(list, image, opaque, width, lineOffsetY + lineHeight, lineOffsetX, lineOffsetY, tileSize - 1);
                    // It is the end of this line for current tile, break now
                    break;
                }
                lineWidth = Math.min(tileContentSize, remainWidth);

                final Tile tile = obtainTile(tileSize);
                tile.image = image;
                tile.setSize(tileSize, lineWidth, lineHeight, lineOffsetX, lineOffsetY);
                tile.setOpaque(opaque);
                list.add(tile);
            }
        }
    }

    public final void setCallback(Callback cb) {
        mCallback = new WeakReference<>(cb);
    }

    public Callback getCallback() {
        if (mCallback != null) {
            return mCallback.get();
        }
        return null;
    }

    public void invalidateSelf() {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.invalidateImageTexture(this);
        }
    }

    public void reset() {
        mReset.lazySet(true);
    }

    @Override
    public void start() {
        final ImageData imageData = mImage.getImageData();
        final boolean startAnimateRunnable;

        synchronized (mLock) {
            mAnimating = true;
            startAnimateRunnable = !(mNeedRecycle || mImage.isRecycled() ||
                    (imageData.isCompleted() && imageData.getFrameCount() == 1) ||
                    mAnimateRunnable != null);
        }

        if (startAnimateRunnable) {
            mAnimateRunnable = new AnimateRunnable();
            sThreadExecutor.execute(mAnimateRunnable);
        }
    }

    @Override
    public void stop() {
        synchronized (mLock) {
            mAnimating = false;
        }
    }

    @Override
    public boolean isRunning() {
        return mRunning.get();
    }

    private boolean uploadNextTile(GLCanvas canvas) {
        if (mUploadIndex == mTiles.length) return true;

        synchronized (mTiles) {
            final Tile next = mTiles[mUploadIndex++];

            // Make sure tile has not already been recycled by the time
            // this is called (race condition in onGLIdle)
            if (next.image != null) {
                final boolean hasBeenLoad = next.isLoaded();
                next.updateContent(canvas);

                // It will take some time for a texture to be drawn for the first
                // time. When scrolling, we need to draw several tiles on the screen
                // at the same time. It may cause a UI jank even these textures has
                // been uploaded.
                if (!hasBeenLoad) next.draw(canvas, 0, 0);
            }
        }
        return mUploadIndex == mTiles.length;
    }

    @Override
    public int getWidth() {
        return mWidth;
    }

    @Override
    public int getHeight() {
        return mHeight;
    }

    // We want to draw the "source" on the "target".
    // This method is to find the "output" rectangle which is
    // the corresponding area of the "src".
    //                                   (x,y)  target
    // (x0,y0)  source                     +---------------+
    //    +----------+                     |               |
    //    | src      |                     | output        |
    //    | +--+     |    linear map       | +----+        |
    //    | +--+     |    ---------->      | |    |        |
    //    |          | by (scaleX, scaleY) | +----+        |
    //    +----------+                     |               |
    //      Texture                        +---------------+
    //                                          Canvas
    private static void mapRect(RectF output,
            RectF src, float x0, float y0, float x, float y, float scaleX,
            float scaleY) {
        output.set(x + (src.left - x0) * scaleX,
                y + (src.top - y0) * scaleY,
                x + (src.right - x0) * scaleX,
                y + (src.bottom - y0) * scaleY);
    }

    @RenderThread
    private void syncFrame() {
        if (mFrameDirty.getAndSet(false)) {
            // invalid tiles
            for (final Tile tile : mTiles) {
                tile.invalidateContent();
            }
        }
    }

    @Override
    public void draw(GLCanvas canvas, int x, int y) {
        draw(canvas, x, y, mWidth, mHeight);
    }

    // Draws the texture on to the specified rectangle.
    @Override
    public void draw(GLCanvas canvas, int x, int y, int w, int h) {
        final RectF src = mSrcRect;
        final RectF dest = mDestRect;
        final float scaleX = (float) w / mWidth;
        final float scaleY = (float) h / mHeight;

        syncFrame();
        for (final Tile t : mTiles) {
            src.set(0, 0, t.width, t.height);
            src.offset(t.offsetX, t.offsetY);
            mapRect(dest, src, 0, 0, x, y, scaleX, scaleY);
            src.offset(t.borderSize - t.offsetX, t.borderSize - t.offsetY);
            canvas.drawTexture(t, src, dest);
        }
    }

    // Draws a sub region of this texture on to the specified rectangle.
    @Override
    public void draw(GLCanvas canvas, RectF source, RectF target) {
        final RectF src = mSrcRect;
        final RectF dest = mDestRect;
        final float x0 = source.left;
        final float y0 = source.top;
        final float x = target.left;
        final float y = target.top;
        final float scaleX = target.width() / source.width();
        final float scaleY = target.height() / source.height();

        syncFrame();
        for (final Tile t : mTiles) {
            src.set(0, 0, t.width, t.height);
            src.offset(t.offsetX, t.offsetY);
            if (!src.intersect(source)) {
                continue;
            }
            mapRect(dest, src, x0, y0, x, y, scaleX, scaleY);
            src.offset(t.borderSize - t.offsetX, t.borderSize - t.offsetY);
            canvas.drawTexture(t, src, dest);
        }
    }

    // Draws a mixed color of this texture and a specified color onto the
    // a rectangle. The used color is: from * (1 - ratio) + to * ratio.
    public void drawMixed(GLCanvas canvas, int color, float ratio,
            int x, int y, int width, int height) {
        final RectF src = mSrcRect;
        final RectF dest = mDestRect;
        final float scaleX = (float) width / mWidth;
        final float scaleY = (float) height / mHeight;

        syncFrame();
        for (final Tile t : mTiles) {
            src.set(0, 0, t.width, t.height);
            src.offset(t.offsetX, t.offsetY);
            mapRect(dest, src, 0, 0, x, y, scaleX, scaleY);
            src.offset(t.borderSize - t.offsetX, t.borderSize - t.offsetY);
            canvas.drawMixed(t, color, ratio, src, dest);
        }
    }

    public void drawMixed(GLCanvas canvas, int color, float ratio,
            RectF source, RectF target) {
        final RectF src = mSrcRect;
        final RectF dest = mDestRect;
        final float x0 = source.left;
        final float y0 = source.top;
        final float x = target.left;
        final float y = target.top;
        final float scaleX = target.width() / source.width();
        final float scaleY = target.height() / source.height();

        syncFrame();
        for (final Tile t : mTiles) {
            src.set(0, 0, t.width, t.height);
            src.offset(t.offsetX, t.offsetY);
            if (!src.intersect(source)) {
                continue;
            }
            mapRect(dest, src, x0, y0, x, y, scaleX, scaleY);
            src.offset(t.borderSize - t.offsetX, t.borderSize - t.offsetY);
            canvas.drawMixed(t, color, ratio, src, dest);
        }
    }

    @Override
    public boolean isOpaque() {
        return mOpaque;
    }

    public boolean isReady() {
        return mUploadIndex == mTiles.length;
    }

    public void recycle() {
        for (final Tile mTile : mTiles) {
            mTile.free();
        }

        synchronized (mImage) {
            if (mLock.isLocked()) {
                mNeedRecycle = true;
            } else {
                mImage.recycle();
                final ImageData imageData = mImage.getImageData();
                if (!imageData.isReferenced()) imageData.recycle();
            }
        }
    }

    public interface Callback {
        void invalidateImageTexture(ImageTexture who);
    }
}
