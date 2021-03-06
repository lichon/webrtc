/*
 * libjingle
 * Copyright 2015 Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.webrtc;

import android.graphics.SurfaceTexture;
import android.opengl.EGLContext;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.TimeUnit;

/**
 * Helper class to create and synchronize access to a SurfaceTexture. The caller will get notified
 * of new frames in onTextureFrameAvailable(), and should call returnTextureFrame() when done with
 * the frame. Only one texture frame can be in flight at once, so returnTextureFrame() must be
 * called in order to receive a new frame. Call disconnect() to stop receiveing new frames and
 * release all resources.
 */
public final class SurfaceTextureHelper {
  private static final String TAG = "SurfaceTextureHelper";
  /**
   * Callback interface for being notified that a new texture frame is available. The calls will be
   * made on a dedicated thread with a bound EGLContext. The thread will be the same throughout the
   * lifetime of the SurfaceTextureHelper instance, but different from the thread calling the
   * SurfaceTextureHelper constructor. The callee is not allowed to make another EGLContext current
   * on the calling thread.
   */
  public interface OnTextureFrameAvailableListener {
    abstract void onTextureFrameAvailable(
        int oesTextureId, float[] transformMatrix, long timestampNs);
  }

  private final HandlerThread thread;
  private final Handler handler;
  private final EglBase eglBase;
  private final SurfaceTexture surfaceTexture;
  private final int oesTextureId;
  private final OnTextureFrameAvailableListener listener;
  // The possible states of this class.
  private boolean hasPendingTexture = false;
  private boolean isTextureInUse = false;
  private boolean isQuitting = false;

  /**
   * Construct a new SurfaceTextureHelper to stream textures to the given |listener|, sharing OpenGL
   * resources with |sharedContext|.
   */
  public SurfaceTextureHelper(EGLContext sharedContext, OnTextureFrameAvailableListener listener) {
    this.listener = listener;
    thread = new HandlerThread(TAG);
    thread.start();
    handler = new Handler(thread.getLooper());

    eglBase = new EglBase(sharedContext, EglBase.ConfigType.PIXEL_BUFFER);
    eglBase.createDummyPbufferSurface();
    eglBase.makeCurrent();

    oesTextureId = GlUtil.generateTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
    surfaceTexture = new SurfaceTexture(oesTextureId);
    surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
      @Override
      public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        hasPendingTexture = true;
        tryDeliverTextureFrame();
      }
    }, handler);

    // Reattach EGL context to private thread.
    eglBase.detachCurrent();
    handler.post(new Runnable() {
      @Override public void run() {
        eglBase.makeCurrent();
      }
    });
  }

  /**
   * Retrieve the underlying SurfaceTexture. The SurfaceTexture should be passed in to a video
   * producer such as a camera or decoder.
   */
  public SurfaceTexture getSurfaceTexture() {
    return surfaceTexture;
  }

  /**
   * Call this function to signal that you are done with the frame received in
   * onTextureFrameAvailable(). Only one texture frame can be in flight at once, so you must call
   * this function in order to receive a new frame.
   */
  public void returnTextureFrame() {
    handler.post(new Runnable() {
      @Override public void run() {
        isTextureInUse = false;
        if (isQuitting) {
          release();
        } else {
          tryDeliverTextureFrame();
        }
      }
    });
  }

  /**
   * Call disconnect() to stop receiving frames and release all resources. This function will block
   * until all frames are returned and all resoureces are released. You are guaranteed to not
   * receive any more onTextureFrameAvailable() after this function returns.
   */
  public void disconnect() {
    handler.postAtFrontOfQueue(new Runnable() {
      @Override public void run() {
        isQuitting = true;
        if (!isTextureInUse) {
          release();
        }
      }
    });
    try {
      thread.join();
    } catch (InterruptedException e) {
      Log.e(TAG, "SurfaceTexture thread was interrupted in join().");
    }
  }

  private void tryDeliverTextureFrame() {
    if (Thread.currentThread() != thread) {
      throw new IllegalStateException("Wrong thread.");
    }
    if (isQuitting || !hasPendingTexture || isTextureInUse) {
      return;
    }
    isTextureInUse = true;
    hasPendingTexture = false;

    surfaceTexture.updateTexImage();
    final float[] transformMatrix = new float[16];
    surfaceTexture.getTransformMatrix(transformMatrix);
    final long timestampNs = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH)
        ? surfaceTexture.getTimestamp()
        : TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());
    listener.onTextureFrameAvailable(oesTextureId, transformMatrix, timestampNs);
  }

  private void release() {
    if (isTextureInUse || !isQuitting) {
      throw new IllegalStateException("Unexpected release.");
    }
    // Release GL resources on dedicated thread.
    handler.post(new Runnable() {
      @Override public void run() {
        GLES20.glDeleteTextures(1, new int[] {oesTextureId}, 0);
        surfaceTexture.release();
        eglBase.release();
      }
    });
    // Quit safely to make sure the clean-up posted above is executed.
    thread.quitSafely();
  }
}
