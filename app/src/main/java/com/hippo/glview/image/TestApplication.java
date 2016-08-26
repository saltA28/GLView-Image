package com.hippo.glview.image;

/*
 * Created by Hippo on 8/27/2016.
 */

import android.app.Application;
import android.os.Debug;
import android.util.Log;

import com.hippo.image.Image;
import com.hippo.yorozuya.FileUtils;
import com.hippo.yorozuya.OSUtils;
import com.hippo.yorozuya.SimpleHandler;

public class TestApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        debugPrint();
    }

    private void debugPrint() {
        new Runnable() {
            @Override
            public void run() {
                Log.i("TAG", "=============================");
                Log.i("TAG", "Java memory: " + FileUtils.readableByteCount(OSUtils.getAppAllocatedMemory(), false));
                Log.i("TAG", "Native memory: " + FileUtils.readableByteCount(Debug.getNativeHeapAllocatedSize(), false));
                Log.i("TAG", "ImageData: " + Image.getNumberOfImageData());
                Log.i("TAG", "ImageRenderer: " + Image.getNumberOfImageRenderer());
                SimpleHandler.getInstance().postDelayed(this, 2000);
            }
        }.run();
    }
}
