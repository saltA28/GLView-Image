package com.hippo.glview.image;

/*
 * Created by Hippo on 8/26/2016.
 */

import android.content.Context;
import android.graphics.Color;

import com.hippo.glview.glrenderer.GLCanvas;
import com.hippo.glview.image.example.R;
import com.hippo.glview.view.GLView;
import com.hippo.image.Image;

public class GLTestView extends GLView implements ImageTexture.Callback {

    private Context mContext;

    private ImageTexture mImageTexture;


    public GLTestView(Context context) {
        mContext = context;
        setBackgroundColor(Color.BLACK);
        reload();
    }

    public void start() {
        if (mImageTexture != null) {
            mImageTexture.start();
        }
    }

    public void reset() {
        if (mImageTexture != null) {
            mImageTexture.reset();
        }
    }

    public void stop() {
        if (mImageTexture != null) {
            mImageTexture.stop();
        }
    }

    public void reload() {
        if (mImageTexture != null) {
            mImageTexture.setCallback(null);
            mImageTexture.recycle();
        }

        mImageTexture = new ImageTexture(Image.decode(mContext.getResources().openRawResource(R.raw.giphy), false));
        mImageTexture.setCallback(this);
    }

    @Override
    public void onRender(GLCanvas canvas) {
        if (mImageTexture != null) {
            final int viewWidth = getWidth();
            final int viewHeight = getHeight();
            if (viewHeight <= 0 || viewHeight <= 0) {
                return;
            }
            final int contentWidth = mImageTexture.getWidth();
            final int contentHeight = mImageTexture.getHeight();

            final float widthScale = (float) viewWidth / contentWidth;
            final float heightScale = (float) viewHeight / contentHeight;
            final float fitScale = Math.min(widthScale, heightScale);

            final int width = (int) (contentWidth * fitScale);
            final int height = (int) (contentHeight * fitScale);
            mImageTexture.draw(canvas, 0, 0, width, height);
        }
    }

    @Override
    public void invalidateImageTexture(ImageTexture who) {
        invalidate();
    }
}
