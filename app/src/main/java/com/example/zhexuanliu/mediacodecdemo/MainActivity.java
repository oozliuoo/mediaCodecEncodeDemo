package com.example.zhexuanliu.mediacodecdemo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

public class MainActivity extends AppCompatActivity {

    private String LOG_TAG = "MEDIA_ENCODE_DEMO";

    // Storage Permissions
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    // parameters for the encoder
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 15;               // 15fps
    private static final int IFRAME_INTERVAL = 10;          // 10 seconds between I-frames

    private static final boolean VERBOSE = true;           // verbose logging
    private static final boolean DEBUG_SAVE_FILE = true;

    // movie length, in frames
    private static final int NUM_FRAMES = 342;               // video length in terms of frame num

    // size of a frame, in pixels
    private int mWidth = 320;
    private int mHeight = 180;

    private int mBitRate = 2 * mWidth * mHeight * FRAME_RATE;
    private static final String DEBUG_FILE_NAME_BASE = "test_encode";

    private MediaCodec mEncoder = null;

    private Context mContext = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        this.mContext = getApplicationContext();

        this.encode();
    }

    private void initCodec() throws IOException {
        MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
        if (codecInfo == null) {
            // Don't fail CTS if they don't have an AVC codec (not here, anyway).
            Log.e(LOG_TAG, "Unable to find an appropriate codec for " + MIME_TYPE);
            return;
        }

        if (VERBOSE) Log.d(LOG_TAG, "found codec: " + codecInfo.getName());

        // for this demo purpose, we are gonna fix color format to be YUV420sp
        int colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
        if (VERBOSE) Log.d(LOG_TAG, "found colorFormat: " + colorFormat);

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        mEncoder = MediaCodec.createByCodecName(codecInfo.getName());
        if (VERBOSE) Log.d(LOG_TAG, "Created Codec");
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();
    }

    /**
     * The actual encode process starts here, including initiating and running the encoder
     *
     */
    private void encode() {
        try {
            initCodec();
            doEncode();

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (VERBOSE) Log.d(LOG_TAG, "releasing codecs");
            if (mEncoder != null) {
                mEncoder.stop();
                mEncoder.release();
            }
        }

    }

    private void NV21ToNV12(byte[] nv21,byte[] nv12,int width,int height){
        if(nv21 == null || nv12 == null)return;
        int framesize = width * height;
        int i = 0,j = 0;
        System.arraycopy(nv21, 0, nv12, 0, framesize);
        for(i = 0; i < framesize; i++){
            nv12[i] = nv21[i];
        }
        for (j = 0; j < framesize/2; j+=2)
        {
            nv12[framesize + j-1] = nv21[j+framesize];
        }
        for (j = 0; j < framesize/2; j+=2)
        {
            nv12[framesize + j] = nv21[j+framesize-1];
        }
    }

    /**
     * The synchronized encode process after codec is properly initiating
     */
    private void doEncode() {
        final int TIMEOUT_USEC = 10000;
        int colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;

        ByteBuffer[] encoderInputBuffers = mEncoder.getInputBuffers();
        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        byte[] frameData = new byte[mWidth * mHeight * 3 / 2];

        // set up output file if any
        FileOutputStream outputStream = null;
        if (DEBUG_SAVE_FILE) {
            // set permission
            verifyStoragePermissions(MainActivity.this);
            File file = getAlbumStorageDir("encode_test", DEBUG_FILE_NAME_BASE + mWidth + "x" + mHeight + ".mp4");
            try {
                outputStream = new FileOutputStream(file);
                Log.d(LOG_TAG, "encoded output will be saved as " + DEBUG_FILE_NAME_BASE + mWidth + "x" + mHeight + ".mp4");
            } catch (IOException ioe) {
                Log.w(LOG_TAG, "Unable to create debug output file " + DEBUG_FILE_NAME_BASE + mWidth + "x" + mHeight + ".mp4");
                throw new RuntimeException(ioe);
            }
        }

        // Loop until the output side is done.
        boolean inputDone = false;
        boolean encoderDone = false;
        boolean outputDone = false;

        int generateIndex = 0;

        while (!outputDone) {
            if (VERBOSE) Log.d(LOG_TAG, "Start looping");

            if (!inputDone) {
                int inputBufIndex = mEncoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (VERBOSE) Log.d(LOG_TAG, "inputBufIndex=" + inputBufIndex);
                if (inputBufIndex >= 0) {
                    long ptsUsec = computePresentationTime(generateIndex);
                    if (generateIndex == NUM_FRAMES) {
                        // Send an empty frame with the end-of-stream flag set.  If we set EOS
                        // on a frame with data, that frame data will be ignored, and the
                        // output will be short one frame.
                        mEncoder.queueInputBuffer(inputBufIndex, 0, 0, ptsUsec,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                        if (VERBOSE) Log.d(LOG_TAG, "sent input EOS (with zero-length frame)");
                    } else {
                        byte[] frameDataNV21 = new byte[frameData.length];
                        readFrame(generateIndex, frameDataNV21);
                        NV21ToNV12(frameDataNV21, frameData, mWidth, mHeight);
                        ByteBuffer inputBuf = encoderInputBuffers[inputBufIndex];
                        // the buffer should be sized to hold one full frame
                        assertTrue(inputBuf.capacity() >= frameData.length);
                        inputBuf.clear();
                        inputBuf.put(frameData);
                        mEncoder.queueInputBuffer(inputBufIndex, 0, frameData.length, ptsUsec, 0);
                        if (VERBOSE) Log.d(LOG_TAG, "submitted frame " + generateIndex + " to enc");
                    }
                    generateIndex++;
                } else {
                    // either all in use, or we timed out during initial setup
                    if (VERBOSE) Log.d(LOG_TAG, "input buffer not available");
                }
            }

            if (!encoderDone) {
                int encoderStatus = mEncoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    if (VERBOSE) Log.d(LOG_TAG, "no output from encoder available");
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not expected for an encoder
                    encoderOutputBuffers = mEncoder.getOutputBuffers();
                    if (VERBOSE) Log.d(LOG_TAG, "encoder output buffers changed");
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // not expected for an encoder
                    MediaFormat newFormat = mEncoder.getOutputFormat();
                    if (VERBOSE) Log.d(LOG_TAG, "encoder output format changed: " + newFormat);
                } else if (encoderStatus < 0) {
                    fail("unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                } else { // encoderStatus >= 0
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        fail("encoderOutputBuffer " + encoderStatus + " was null");
                    }
                    // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                    encodedData.position(info.offset);
                    encodedData.limit(info.offset + info.size);
                    // encodedSize += info.size;
                    if (outputStream != null) {
                        byte[] data = new byte[info.size];
                        encodedData.get(data);
                        encodedData.position(info.offset);
                        try {
                            outputStream.write(data);
                        } catch (IOException ioe) {
                            Log.w(LOG_TAG, "failed writing debug data to file");
                            throw new RuntimeException(ioe);
                        }
                    }
                    /*
                     * This part was copied from the sample code, which shouldnt be needed in our demo

                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Codec config info.  Only expected on first packet.  One way to
                        // handle this is to manually stuff the data into the MediaFormat
                        // and pass that to configure().  We do that here to exercise the API.
                        // assertFalse(decoderConfigured);
                        MediaFormat format =
                                MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
                        format.setByteBuffer("csd-0", encodedData);
                        decoder.configure(format, toSurface ? outputSurface.getSurface() : null,
                                null, 0);
                        decoder.start();
                        decoderInputBuffers = decoder.getInputBuffers();
                        decoderOutputBuffers = decoder.getOutputBuffers();
                        decoderConfigured = true;
                        if (VERBOSE) Log.d(TAG, "decoder configured (" + info.size + " bytes)");
                    } else {
                        // Get a decoder input buffer, blocking until it's available.
                        assertTrue(decoderConfigured);
                        int inputBufIndex = decoder.dequeueInputBuffer(-1);
                        ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
                        inputBuf.clear();
                        inputBuf.put(encodedData);
                        decoder.queueInputBuffer(inputBufIndex, 0, info.size,
                                info.presentationTimeUs, info.flags);
                        encoderDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (VERBOSE) Log.d(TAG, "passed " + info.size + " bytes to decoder"
                                + (encoderDone ? " (EOS)" : ""));
                    }
                    */
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (VERBOSE) Log.d(LOG_TAG, "output EOS");
                        outputDone = true;
                    }
                    mEncoder.releaseOutputBuffer(encoderStatus, false);
                }
            }
        }

        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException ioe) {
                Log.w(LOG_TAG, "failed closing debug file");
                throw new RuntimeException(ioe);
            }
        }
    }

    public File getAlbumStorageDir(String albumName, String filename) {
        // Get the directory for the user's public pictures directory.
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), albumName);
        if (!dir.mkdirs()) {
            Log.e(LOG_TAG, "Directory not created");
        }

        File file = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), albumName + "/" + filename);

        try {
            file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return file;
    }

    /**
     * Read in a frame from the asset images
     *
     * @param index - index of frame reading, used to locate yuv file
     * @param frameData - buffer to receive frame input
     */
    private void readFrame(int index, byte[] frameData) {
        // Set to zero.  In YUV this is a dull green.
        Arrays.fill(frameData, (byte) 0);

        // set permission
        verifyStoragePermissions(MainActivity.this);

        AssetManager am = mContext.getAssets();
        InputStream is = null;

        // read input
        byte[] source = null;
        try {
            is = am.open("yuvTest/scaled" + (index + 1) + ".yuv");
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            byte[] buffer = new byte[0xFFFF];

            for (int len; (len = is.read(buffer)) != -1;)
                os.write(buffer, 0, len);

            os.flush();

            source = os.toByteArray();

            System.arraycopy(source, 0, frameData, 0, source.length);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Verify storage permissions in this demo, if not presented, then request it
     *
     * @param activity - Activity requesting permission
     */
    public void verifyStoragePermissions(Activity activity) {
        // Check if we have write permission
        int permission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                    activity,
                    PERMISSIONS_STORAGE,
                    REQUEST_EXTERNAL_STORAGE
            );
        }
    }

    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no
     * match was found.
     */
    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    /**
     * Generates the presentation time for frame N, in microseconds.
     */
    private static long computePresentationTime(int frameIndex) {
        return 132 + frameIndex * 1000000 / FRAME_RATE;
    }
}
