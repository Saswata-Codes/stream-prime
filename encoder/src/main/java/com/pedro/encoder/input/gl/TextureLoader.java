/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.encoder.input.gl;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.pedro.encoder.utils.gl.GlUtil;

/**
 * Created by pedro on 9/10/17.
 */

@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class TextureLoader {

  public int[] load(Bitmap[] bitmaps) {
    return load(bitmaps, true);
  }

  /**
   * Upload bitmaps to GL, optionally retaining their pixel storage for a reusable composition
   * canvas. Existing stream objects keep the historical recycle-after-upload behavior.
   */
  public int[] load(Bitmap[] bitmaps, boolean recycleAfterUpload) {
    int[] textureId = new int[bitmaps.length];
    GlUtil.createTextures(bitmaps.length, textureId, 0);
    for (int i = 0; i < bitmaps.length; i++) {
      if (bitmaps[i] != null && !bitmaps[i].isRecycled()) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[i]);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmaps[i], 0);
        if (recycleAfterUpload) bitmaps[i].recycle();
      }
      if (recycleAfterUpload) bitmaps[i] = null;
    }
    return textureId;
  }

  /** Update an existing same-sized texture without allocating a new GL texture object. */
  public void update(int textureId, Bitmap bitmap) {
    if (textureId <= 0 || bitmap == null || bitmap.isRecycled()) return;
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
  }
}
