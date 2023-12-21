package com.example.edgedashanalytics.advanced.common;

import android.util.Log;

public class FrameResult {
    private static final String TAG = "FrameResult";
    public long timestamp;
    public int frameNum;
    public int workerNum;
    public boolean isInner;
    public long processTime;
    public long networkTime;

    public FrameResult(long timestamp, int frameNum, int workerNum, boolean isInner, long processTime, long networkTime) {
        //Log.v(TAG, "processTime = " + processTime);
        this.timestamp = timestamp;
        this.frameNum = frameNum;
        this.workerNum = workerNum;
        this.isInner = isInner;
        this.processTime = processTime;
        this.networkTime = networkTime;
    }
}
