/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.exoplayer.video;

import android.view.Surface;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A provider of {@link VideoSink VideoSinks}. */
@UnstableApi
public interface VideoSinkProvider {

  /**
   * Sets the {@link VideoFrameReleaseControl} that will be used for releasing of video frames
   * during rendering.
   *
   * <p>Must be called before the first {@linkplain #getSink() sink} is {@linkplain
   * VideoSink#initialize(Format, Clock) initialized}.
   */
  void setVideoFrameReleaseControl(VideoFrameReleaseControl videoFrameReleaseControl);

  /**
   * Returns the {@link VideoFrameReleaseControl} that will be used for releasing of video frames
   * during rendering.
   *
   * <p>If this value is {@code null}, it must be {@linkplain #setVideoFrameReleaseControl set} to a
   * non-null value before rendering begins.
   */
  @Nullable VideoFrameReleaseControl getVideoFrameReleaseControl();

  /** Sets video effects on this provider to apply immediately. */
  void setVideoEffects(List<Effect> videoEffects);

  /**
   * Sets video effects on this provider to apply when the next stream is {@linkplain
   * VideoSink#registerInputStream(int, Format) registered} on the {@link #getSink() VideoSink}.
   */
  void setPendingVideoEffects(List<Effect> videoEffects);

  /** Returns a {@link VideoSink} to forward video frames for processing. */
  VideoSink getSink();

  /** Sets the output surface info. */
  void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution);

  /** Clears the set output surface info. */
  void clearOutputSurfaceInfo();

  /** Releases the sink provider. */
  void release();
}
