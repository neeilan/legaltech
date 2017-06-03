package com.constantin.wilson.FPV_VR;

import android.content.pm.ActivityInfo;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

public class TextureViewActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener{
    private TextureView mTextureView;
    private UdpVideoReceiver mDecoder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        mTextureView = new TextureView(this);
        mTextureView.setSurfaceTextureListener(this);
        setContentView(mTextureView);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Surface mDecoderSurface=new Surface(surface);
        mDecoder=new UdpVideoReceiver(mDecoderSurface,5000,this);
        mDecoder.startDecoding();

    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if(mDecoder != null){
            mDecoder.stopDecoding();
        }
        return false;
    }
    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

}
