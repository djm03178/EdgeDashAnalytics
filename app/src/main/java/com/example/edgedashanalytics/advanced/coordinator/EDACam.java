package com.example.edgedashanalytics.advanced.coordinator;

import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.Size;

import com.example.edgedashanalytics.page.main.MainActivity;
import com.example.edgedashanalytics.util.Constants;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class EDACam extends Thread {
    static final String TAG = "EDACam";

    private final String ip;
    private final int msgCode;

    public ObjectInputStream inStream;
    public ObjectOutputStream outStream;
    private Socket socket;
    public Parameter camParameter;
    public boolean isInner;

    public EDACam(String ip, boolean isInner) {
        this.ip = ip;
        this.isInner = isInner;
        this.msgCode = isInner ? Constants.IMAGE_INNER : Constants.IMAGE_OUTER;
        this.socket = null;
        camParameter = new Parameter(isInner);
    }

    @Override
    public void run() {
        try {
            setup();
            doWork();
        } catch (Exception e) {
            e.printStackTrace();
            try {
                Thread.sleep(1000);
            } catch (Exception e2) {
                e2.printStackTrace();
            }
            if (socket != null && socket.isConnected())
                try { socket.close(); } catch (Exception e2) { e2.printStackTrace(); }
        }
    }

    private void setup() throws Exception {
        while (true) {
            try {
                socket = new Socket(ip, 5555);
            } catch (Exception e) {
                Log.v(TAG, "cannot connect to camera " + ip + "yet, retrying in 1s...");
                Thread.sleep(1000);
                continue;
            }
            break;
        }

        ObjectOutputStream tempOutStream = new ObjectOutputStream(socket.getOutputStream());
        inStream = new ObjectInputStream(socket.getInputStream());

        // to avoid race condition, make sure our first signal is fully sent to the camera
        // before we perform the periodical checks
        // First time connected, send initial settings
        sendSettings(tempOutStream);
        outStream = tempOutStream;
    }

    private void doWork() throws Exception {
        while (true) {
            int frameNum = inStream.readInt();
            byte[] data = (byte[]) inStream.readObject();
            int waiting = inStream.readInt();

            int mx = Math.max(waiting, Communicator.msgQueue.size());

            if (mx >= 2) {
                if (mx >= 4) {
                    Controller.queueWarn = 2;
                }
                else if (Controller.queueWarn == 0) {
                    Controller.queueWarn = 1;
                }
                continue;
            }

            CommunicatorMessage msg = new CommunicatorMessage(1, isInner, frameNum, data, System.currentTimeMillis());
            Communicator.msgQueue.put(msg);
        }
    }

    public void sendSettings(ObjectOutputStream outputStream) {
        sendSettings(outputStream, (int) Math.round(camParameter.fps));
    }

    public static void sendSettings(ObjectOutputStream outputStream, int frameRate) {
        try {
            outputStream.writeInt(frameRate);
            outputStream.flush();
            outputStream.reset();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
