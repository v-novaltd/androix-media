/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.extractor.ts;

import static java.lang.Math.min;
import static java.lang.Math.max;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import java.io.IOException;

/**
 * A reader that can extract the approximate duration from a given MPEG transport stream (TS).
 *
 * <p>This reader extracts the duration by reading DTS values of the first PES PID packets at the
 * start and at the end of the stream, calculating the difference, and converting that into stream
 * duration. Should DTS not be available this reader falls back to using PCR, reading its values
 * from the PCR PID packets at the start and at the end of the stream, calculating the difference,
 * and converting that into stream duration. This reader also handles the case when a single PCR
 * wraparound takes place within the stream, which can make PCR values at the beginning of the
 * stream larger than PCR values at the end. This class can only be used once to read duration from
 * a given stream, and the usage of the class is not thread-safe, so all calls should be made from
 * the same thread.
 */
/* package */ final class TsDurationReader {

  private static final String TAG = "TsDurationReader";

  private final int timestampSearchBytes;
  private final TimestampAdjuster timestampAdjuster;
  private final ParsableByteArray packetBuffer;

  private boolean isDurationReadFromDts;
  private boolean isDurationReadFromPcr;
  private boolean isFirstPcrValueRead;
  private boolean isLastPcrValueRead;
  private boolean isFirstDtsValueRead;
  private boolean isSecondDtsValueRead;
  private boolean isLastDtsValueRead;

  private long firstPcrValue;
  private long lastPcrValue;
  private long firstDtsValue;
  private long secondDtsValue;
  private long lastDtsValue;

  private int firstPcrPosition;
  private int firstDtsPosition;

  private long durationUs;

  /* package */ TsDurationReader(int timestampSearchBytes) {
    this.timestampSearchBytes = timestampSearchBytes;
    timestampAdjuster = new TimestampAdjuster(/* firstSampleTimestampUs= */ 0);
    firstPcrValue = C.TIME_UNSET;
    lastPcrValue = C.TIME_UNSET;
    firstDtsValue = C.TIME_UNSET;
    secondDtsValue = C.TIME_UNSET;
    lastDtsValue = C.TIME_UNSET;
    firstPcrPosition = C.INDEX_UNSET;
    firstDtsPosition = C.INDEX_UNSET;
    durationUs = C.TIME_UNSET;
    packetBuffer = new ParsableByteArray();
  }

  /** Returns true if a TS duration has been read. */
  public boolean isDurationReadFinished() {
    return isDurationReadFromDts && isDurationReadFromPcr;
  }

  /**
   * Reads a TS duration from the input, using the DTS values from the given PES PID,
   * if the input lacks DTSes it falls back to using PCR values from the given PCR PID.
   *
   * <p>This reader reads the duration by reading DTS/PCR values of the PES/PCR PID packets at the
   * start and at the end of the stream, calculating the difference, and converting that into
   * stream duration. The DTS reader also adjusts for frame duration of the last frame.
   *
   * @param input The {@link ExtractorInput} from which data should be read.
   * @param seekPositionHolder If {@link Extractor#RESULT_SEEK} is returned, this holder is updated
   *     to hold the position of the required seek.
   * @param pesPid The PID of the packet stream within this TS stream that contains DTS values.
   * @param pcrPid The PID of the packet stream within this TS stream that contains PCR values.
   * @return One of the {@code RESULT_} values defined in {@link Extractor}.
   * @throws IOException If an error occurred reading from the input.
   */
  public @Extractor.ReadResult int readDuration(
      ExtractorInput input, PositionHolder seekPositionHolder, int pesPid, int pcrPid) throws IOException {
    if (!isDurationReadFromDts) {
      return readDurationFromDts(input, seekPositionHolder, pesPid);
    }
    return readDurationFromPcr(input, seekPositionHolder, pcrPid);
  }

  /**
   * Reads a TS duration from the input, using the PCR values from the given PCR PID.
   *
   * <p>This reader reads the duration by reading PCR values of the PCR PID packets at the start and
   * at the end of the stream, calculating the difference, and converting that into stream duration.
   *
   * @param input The {@link ExtractorInput} from which data should be read.
   * @param seekPositionHolder If {@link Extractor#RESULT_SEEK} is returned, this holder is updated
   *     to hold the position of the required seek.
   * @param pcrPid The PID of the packet stream within this TS stream that contains PCR values.
   * @return One of the {@code RESULT_} values defined in {@link Extractor}.
   * @throws IOException If an error occurred reading from the input.
   */
  private @Extractor.ReadResult int readDurationFromPcr(
      ExtractorInput input, PositionHolder seekPositionHolder, int pcrPid) throws IOException {
    if (pcrPid <= 0) {
      isDurationReadFromPcr = true;
      return Extractor.RESULT_CONTINUE;
    }
    if (!isFirstPcrValueRead) {
      return readFirstPcrValue(input, seekPositionHolder, pcrPid);
    }
    if (firstPcrValue == C.TIME_UNSET) {
      isDurationReadFromPcr = true;
      return Extractor.RESULT_CONTINUE;
    }
    if (!isLastPcrValueRead) {
      return readLastPcrValue(input, seekPositionHolder, pcrPid);
    }
    if (lastPcrValue == C.TIME_UNSET) {
      isDurationReadFromPcr = true;
      return Extractor.RESULT_CONTINUE;
    }

    long minPcrPositionUs = timestampAdjuster.adjustTsTimestamp(firstPcrValue);
    long maxPcrPositionUs = timestampAdjuster.adjustTsTimestamp(lastPcrValue);
    durationUs = maxPcrPositionUs - minPcrPositionUs;
    if (durationUs < 0) {
      durationUs = C.TIME_UNSET;
    }
    return finishReadDuration(input);
  }

  /**
   * Reads a TS duration from the input, using the DTS values from the given PES PID.
   *
   * <p>This reader reads the duration by reading first, second and last DTS values from the PES PID
   * packets in the stream. The differences between second and first and between last
   * and first give respectively frame duration and stream duration less one frame duration, so
   * the total stream duration is the sum of these two differences.
   *
   * @param input The {@link ExtractorInput} from which data should be read.
   * @param seekPositionHolder If {@link Extractor#RESULT_SEEK} is returned, this holder is updated
   *     to hold the position of the required seek.
   * @param pesPid The PID of the packet stream within this TS stream that contains DTS values.
   * @return One of the {@code RESULT_} values defined in {@link Extractor}.
   * @throws IOException If an error occurred reading from the input.
   */
  private @Extractor.ReadResult int readDurationFromDts(
      ExtractorInput input, PositionHolder seekPositionHolder, int pesPid) throws IOException {
    if (pesPid <= 0) {
      isDurationReadFromDts = true;
      return Extractor.RESULT_CONTINUE;
    }
    if (!isFirstDtsValueRead) {
      return readFirstDtsValue(input, seekPositionHolder, pesPid);
    }
    if (firstDtsValue == C.TIME_UNSET) {
      isDurationReadFromDts = true;
      return Extractor.RESULT_CONTINUE;
    }
    if (!isSecondDtsValueRead) {
      return readSecondDtsValue(input, seekPositionHolder, pesPid);
    }
    if (secondDtsValue == C.TIME_UNSET) {
      isDurationReadFromDts = true;
      return Extractor.RESULT_CONTINUE;
    }
    if (!isLastDtsValueRead) {
      return readLastDtsValue(input, seekPositionHolder, pesPid);
    }
    if (lastDtsValue == C.TIME_UNSET) {
      isDurationReadFromDts = true;
      return Extractor.RESULT_CONTINUE;
    }

    long firstDtsPositionUs = timestampAdjuster.adjustTsTimestamp(firstDtsValue);
    long secondDtsPositionUs = timestampAdjuster.adjustTsTimestamp(secondDtsValue);
    long frameDurationUs = secondDtsPositionUs - firstDtsPositionUs;
    long lastDtsPositionUs = timestampAdjuster.adjustTsTimestamp(lastDtsValue);
    durationUs = frameDurationUs + lastDtsPositionUs - firstDtsPositionUs;
    if (durationUs < 0) {
      durationUs = C.TIME_UNSET;
    }
    return finishReadDuration(input);
  }

  /**
   * Returns the duration last read from {@link #readDuration(ExtractorInput, PositionHolder, int, int)}.
   */
  public long getDurationUs() {
    return durationUs;
  }

  /**
   * Returns the {@link TimestampAdjuster} that this class uses to adjust timestamps read from the
   * input TS stream.
   */
  public TimestampAdjuster getTimestampAdjuster() {
    return timestampAdjuster;
  }

  private int finishReadDuration(ExtractorInput input) {
    packetBuffer.reset(Util.EMPTY_BYTE_ARRAY);
    isDurationReadFromDts = true;
    isDurationReadFromPcr = true;
    input.resetPeekPosition();
    return Extractor.RESULT_CONTINUE;
  }

  private int readFirstPcrValue(ExtractorInput input, PositionHolder seekPositionHolder, int pcrPid)
      throws IOException {
    int bytesToSearch = (int) min(timestampSearchBytes, input.getLength());
    int searchStartPosition = 0;
    if (input.getPosition() != searchStartPosition) {
      seekPositionHolder.position = searchStartPosition;
      return Extractor.RESULT_SEEK;
    }

    packetBuffer.reset(bytesToSearch);
    input.resetPeekPosition();
    input.peekFully(packetBuffer.getData(), /* offset= */ 0, bytesToSearch);

    firstPcrValue = readFirstPcrValueFromBuffer(packetBuffer, pcrPid);
    isFirstPcrValueRead = true;
    return Extractor.RESULT_CONTINUE;
  }

  private long readFirstPcrValueFromBuffer(ParsableByteArray packetBuffer, int pcrPid) {
    int searchStartPosition = packetBuffer.getPosition();
    int searchEndPosition = packetBuffer.limit();
    for (int searchPosition = searchStartPosition;
        searchPosition < searchEndPosition;
        searchPosition++) {
      if (packetBuffer.getData()[searchPosition] != TsExtractor.TS_SYNC_BYTE) {
        continue;
      }
      long pcrValue = TsUtil.readPcrFromPacket(packetBuffer, searchPosition, pcrPid);
      if (pcrValue != C.TIME_UNSET) {
        firstPcrPosition = packetBuffer.getPosition();
        return pcrValue;
      }
    }
    return C.TIME_UNSET;
  }

  private int readLastPcrValue(ExtractorInput input, PositionHolder seekPositionHolder, int pcrPid)
      throws IOException {
    long inputLength = input.getLength();
    int bytesToSearch = (int) min(timestampSearchBytes, inputLength - firstPcrPosition);
    long searchStartPosition = inputLength - bytesToSearch;
    if (input.getPosition() != searchStartPosition) {
      seekPositionHolder.position = searchStartPosition;
      return Extractor.RESULT_SEEK;
    }

    packetBuffer.reset(bytesToSearch);
    input.resetPeekPosition();
    input.peekFully(packetBuffer.getData(), /* offset= */ 0, bytesToSearch);

    lastPcrValue = readLastPcrValueFromBuffer(packetBuffer, pcrPid);
    isLastPcrValueRead = true;
    return Extractor.RESULT_CONTINUE;
  }

  private long readLastPcrValueFromBuffer(ParsableByteArray packetBuffer, int pcrPid) {
    int searchStartPosition = packetBuffer.getPosition();
    int searchEndPosition = packetBuffer.limit();
    // We start searching 'TsExtractor.TS_PACKET_SIZE' bytes from the end to prevent trying to read
    // from an incomplete TS packet.
    for (int searchPosition = searchEndPosition - TsExtractor.TS_PACKET_SIZE;
        searchPosition >= searchStartPosition;
        searchPosition--) {
      if (!TsUtil.isStartOfTsPacket(
          packetBuffer.getData(), searchStartPosition, searchEndPosition, searchPosition)) {
        continue;
      }
      long pcrValue = TsUtil.readPcrFromPacket(packetBuffer, searchPosition, pcrPid);
      if (pcrValue != C.TIME_UNSET) {
        return pcrValue;
      }
    }
    return C.TIME_UNSET;
  }

  private int readFirstDtsValue(ExtractorInput input, PositionHolder seekPositionHolder, int pesPid)
      throws IOException {
    int bytesToSearch = (int) min(timestampSearchBytes, input.getLength());
    int searchStartPosition = 0;
    if (input.getPosition() != searchStartPosition) {
      seekPositionHolder.position = searchStartPosition;
      return Extractor.RESULT_SEEK;
    }

    packetBuffer.reset(bytesToSearch);
    input.resetPeekPosition();
    input.peekFully(packetBuffer.getData(), /* offset= */ 0, bytesToSearch);

    firstDtsValue = readFirstDtsValueFromBuffer(packetBuffer, pesPid);
    isFirstDtsValueRead = true;
    return Extractor.RESULT_CONTINUE;
  }

  private long readFirstDtsValueFromBuffer(ParsableByteArray packetBuffer, int pesPid) {
    int searchStartPosition = packetBuffer.getPosition();
    int searchEndPosition = packetBuffer.limit();
    for (int searchPosition = searchStartPosition;
        searchPosition < searchEndPosition;
        searchPosition++) {
      if (packetBuffer.getData()[searchPosition] != TsExtractor.TS_SYNC_BYTE) {
        continue;
      }
      long dtsValue = TsUtil.readDtsFromPacket(packetBuffer, searchPosition, pesPid);
      if (dtsValue != C.TIME_UNSET) {
        firstDtsPosition = packetBuffer.getPosition();
        return dtsValue;
      }
    }
    return C.TIME_UNSET;
  }

  private int readSecondDtsValue(ExtractorInput input, PositionHolder seekPositionHolder, int pesPid)
      throws IOException {
    int searchStartPosition = firstDtsPosition;
    int bytesToSearch = (int) min(timestampSearchBytes, input.getLength() - searchStartPosition);
    if (input.getPosition() != searchStartPosition) {
      seekPositionHolder.position = searchStartPosition;
      return Extractor.RESULT_SEEK;
    }

    packetBuffer.reset(bytesToSearch);
    input.resetPeekPosition();
    input.peekFully(packetBuffer.getData(), /* offset= */ 0, bytesToSearch);

    secondDtsValue = readFirstDtsValueFromBuffer(packetBuffer, pesPid);
    isSecondDtsValueRead = true;
    return Extractor.RESULT_CONTINUE;
  }

  private int readLastDtsValue(ExtractorInput input, PositionHolder seekPositionHolder, int pesPid)
      throws IOException {
    long inputLength = input.getLength();
    int bytesToSearch = (int) min(timestampSearchBytes, inputLength - firstDtsPosition);
    long searchStartPosition = inputLength - bytesToSearch;
    if (input.getPosition() != searchStartPosition) {
      seekPositionHolder.position = searchStartPosition;
      return Extractor.RESULT_SEEK;
    }

    packetBuffer.reset(bytesToSearch);
    input.resetPeekPosition();
    input.peekFully(packetBuffer.getData(), /* offset= */ 0, bytesToSearch);

    lastDtsValue = readLastDtsValueFromBuffer(packetBuffer, pesPid);
    isLastDtsValueRead = true;
    return Extractor.RESULT_CONTINUE;
  }

  private long readLastDtsValueFromBuffer(ParsableByteArray packetBuffer, int pesPid) {
    int searchStartPosition = packetBuffer.getPosition();
    int searchEndPosition = packetBuffer.limit();
    // We start searching 'TsExtractor.TS_PACKET_SIZE' bytes from the end to prevent trying to read
    // from an incomplete TS packet.
    for (int searchPosition = searchEndPosition - TsExtractor.TS_PACKET_SIZE;
        searchPosition >= searchStartPosition;
        searchPosition--) {
      if (!TsUtil.isStartOfTsPacket(
          packetBuffer.getData(), searchStartPosition, searchEndPosition, searchPosition)) {
        continue;
      }
      long dtsValue = TsUtil.readDtsFromPacket(packetBuffer, searchPosition, pesPid);
      if (dtsValue != C.TIME_UNSET) {
        return dtsValue;
      }
    }
    return C.TIME_UNSET;
  }
}
