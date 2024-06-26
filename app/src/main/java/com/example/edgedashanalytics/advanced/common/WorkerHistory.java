package com.example.edgedashanalytics.advanced.common;

import java.util.ArrayDeque;
import java.util.Deque;

public class WorkerHistory {
    private static final String TAG = "WorkerHistory";
    public Deque<AnalysisResult> history;
    public static final long HISTORY_DURATION = 2000;

    public double processTime;

    public int totalProcessTime;
    public int totalNetworkTime;

    public WorkerHistory(long defaultProcessTime) {
        history = new ArrayDeque<>();
        processTime = defaultProcessTime;
        totalProcessTime = 0;
    }

    public WorkerHistory(WorkerHistory org) {
        history = new ArrayDeque<>();
        processTime = org.processTime;
        totalProcessTime = org.totalProcessTime;
        totalNetworkTime = org.totalNetworkTime;
    }

    public synchronized void addResult(AnalysisResult result) {
        history.add(result);
        totalProcessTime += result.processTime;
        totalNetworkTime += result.networkTime;

        calcProcessTime();
    }

    public synchronized void removeOldResults() {
        long curTime = System.currentTimeMillis();
        while (!history.isEmpty() && curTime - history.peek().timestamp > HISTORY_DURATION) {
            AnalysisResult old = history.pop();
            totalProcessTime -= old.processTime;
            totalNetworkTime -= old.networkTime;
        }

        calcProcessTime();
    }

    private void calcProcessTime() {
        int sz = history.size();
        if (sz != 0)
            processTime = (double) totalProcessTime / sz;
    }
}
