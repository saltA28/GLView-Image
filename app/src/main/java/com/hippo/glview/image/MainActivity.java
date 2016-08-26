package com.hippo.glview.image;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.hippo.glview.image.example.R;
import com.hippo.glview.view.GLRootView;
import com.hippo.image.Image;
import com.hippo.yorozuya.MathUtils;
import com.hippo.yorozuya.SimpleHandler;

public class MainActivity extends Activity {

    private GLTestView mGLTestView;
    private TextView mTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Image.createBuffer(1024 * 1024);

        mTextView = (TextView) findViewById(R.id.text);
        final GLRootView glRootView = (GLRootView) findViewById(R.id.gl_root);
        final GLTestView glTestView = new GLTestView(this);
        mGLTestView = glTestView;
        glRootView.setContentPane(glTestView);

        findViewById(R.id.start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                glTestView.start();
            }
        });
        findViewById(R.id.reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                glTestView.reset();
            }
        });
        findViewById(R.id.stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                glTestView.stop();
            }
        });
        findViewById(R.id.reload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                glTestView.reload();
            }
        });

        debugPrint();
    }

    private void debugPrint() {
        new Runnable() {
            @Override
            public void run() {
                final int i = MathUtils.random(4);
                switch (i) {
                    case 0:
                        mGLTestView.start();
                        break;
                    case 1:
                        mGLTestView.reset();
                        break;
                    case 2:
                        mGLTestView.stop();
                        break;
                    case 3:
                        mGLTestView.reload();
                        mGLTestView.start();
                        break;
                }

                mTextView.setText("ImageData: " + Image.getNumberOfImageData() + "\n" +
                        "ImageRenderer: " + Image.getNumberOfImageRenderer() + "\n" +
                        "i = " + i);

                SimpleHandler.getInstance().postDelayed(this, MathUtils.random(1000, 3000));
            }
        }.run();
    }
}
