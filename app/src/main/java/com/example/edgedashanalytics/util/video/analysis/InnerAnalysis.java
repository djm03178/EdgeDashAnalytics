package com.example.edgedashanalytics.util.video.analysis;

import static com.example.edgedashanalytics.page.main.MainActivity.I_TAG;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.example.edgedashanalytics.R;
import com.example.edgedashanalytics.advanced.worker.InnerProcessor;

import org.apache.commons.lang3.ArrayUtils;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


// https://www.tensorflow.org/lite/examples/pose_estimation/overview
// https://www.tensorflow.org/lite/tutorials/pose_classification
// https://github.com/tensorflow/examples/tree/master/lite/examples/pose_estimation/android
// https://github.com/tensorflow/examples/blob/master/lite/examples/pose_estimation/android/app/src/main/java/org/tensorflow/lite/examples/poseestimation/ml/MoveNet.kt
public class InnerAnalysis extends VideoAnalysis {
    private static final String TAG = InnerAnalysis.class.getSimpleName();

    private static final float MIN_SCORE = 0.2f;

    private static RectF cropRegion;

    // Let's do this per thread instead of sharing across threads
    //private static final BlockingQueue<Interpreter> interpreterQueue = new LinkedBlockingQueue<>(THREAD_NUM);
    //private static final BlockingQueue<ImageProcessor> processorQueue = new LinkedBlockingQueue<>(THREAD_NUM);

    private static final int[] models = {
            R.string.movenet_lightning_key,
            //R.string.movenet_thunder_key
    };

    public int inputWidth, inputHeight;
    public int[] outputShape;

    class Analyzer {
        Interpreter interpreter;
        ImageProcessor imageProcessor;
        public int inputWidth, inputHeight;
        public int[] outputShape;

        public Analyzer(Interpreter interpreter) {
            this.interpreter = interpreter;
            inputWidth = interpreter.getInputTensor(0).shape()[1];
            inputHeight = interpreter.getInputTensor(0).shape()[2];
            outputShape = interpreter.getOutputTensor(0).shape();

            int size = Math.min(inputHeight, inputWidth);

            imageProcessor = new ImageProcessor.Builder()
                    .add(new ResizeWithCropOrPadOp(size, size))
                    .add(new ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
                    .build();
        }
    }

    private ArrayList<Analyzer> analyzerList = new ArrayList<>();

    public InnerAnalysis(Context context) {
        super(context);

        Interpreter.Options options = new Interpreter.Options();
        options.setUseXNNPACK(true);
        options.setNumThreads(TF_THREAD_NUM);

        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        for (int model : models) {
            String modelFilename = pref.getString("pose_model", context.getString(model));

            try {
                Interpreter interpreter = new Interpreter(FileUtil.loadMappedFile(context, modelFilename), options);
                if (analyzerList.isEmpty()) {
                    inputWidth = interpreter.getInputTensor(0).shape()[1];
                    inputHeight = interpreter.getInputTensor(0).shape()[2];
                    outputShape = interpreter.getOutputTensor(0).shape();
                    InnerProcessor.inputWidth = inputWidth;
                }
                analyzerList.add(new Analyzer(interpreter));
            } catch (IOException e) {
                Log.w(I_TAG, String.format("Model failure:\n  %s", e.getMessage()));
            }
        }
    }

    long frameCnt = 0;
    long totalTime = 0;

    List<Frame> processFrame(Bitmap bitmap, int frameIndex, float scaleFactor) {
        long startTime = System.currentTimeMillis();
        frameCnt++;

        ArrayList<Frame> resultList = new ArrayList<>();

        for (Analyzer analyzer : analyzerList) {
            setup(analyzer.inputWidth, analyzer.inputHeight);
            RectF rect = new RectF(
                    cropRegion.left * bitmap.getWidth(),
                    cropRegion.top * bitmap.getHeight(),
                    cropRegion.right * bitmap.getWidth(),
                    cropRegion.bottom * bitmap.getHeight()
            );

            Bitmap detectBitmap = Bitmap.createBitmap((int) rect.width(), (int) rect.height(),
                    Bitmap.Config.ARGB_8888);
            // Might just be for visualisation, may be unnecessary
            Canvas canvas = new Canvas(detectBitmap);
            canvas.drawBitmap(bitmap, -rect.left, -rect.top, null);

            Interpreter interpreter = analyzer.interpreter;
            float totalScore = 0;
            int numKeyPoints = analyzer.outputShape[2];

            TensorBuffer outputTensor = TensorBuffer.createFixedSize(analyzer.outputShape, DataType.FLOAT32);
            float widthRatio = detectBitmap.getWidth() / (float) analyzer.inputWidth;
            float heightRatio = detectBitmap.getHeight() / (float) analyzer.inputHeight;

            if (interpreter == null) {
                Log.w(I_TAG, String.format("Interpreter for frame %s is null", frameIndex));
                return null;
            }

            ImageProcessor imageProcessor = analyzer.imageProcessor;
            TensorImage inputTensor = imageProcessor.process(TensorImage.fromBitmap(bitmap));

            interpreter.run(inputTensor.getBuffer(), outputTensor.getBuffer().rewind());

            float[] output = outputTensor.getFloatArray();
            List<Float> positions = new ArrayList<>();
            List<KeyPoint> keyPoints = new ArrayList<>();

            // Don't bother keeping results for keyPoints of lower body parts,
            //  lower body part indexes start at BodyPart.LOWER_INDEX
            for (int a = 0; a < numKeyPoints && a < BodyPart.LOWER_INDEX; a++) {
                float x = output[a * 3 + 1] * analyzer.inputWidth * widthRatio;
                float y = output[a * 3] * analyzer.inputHeight * heightRatio;

                positions.add(x);
                positions.add(y);

                float score = output[a * 3 + 2];
                keyPoints.add(new KeyPoint(BodyPart.AS_ARRAY[a], new PointF(x, y), score));
                totalScore += score;
            }

            // Adjust keypoint coordinates to align with original bitmap dimensions
            Matrix matrix = new Matrix();
            float[] points = ArrayUtils.toPrimitive(positions.toArray(new Float[0]), 0f);

            matrix.postTranslate(rect.left, rect.top);
            matrix.mapPoints(points);

            for (int i = 0; i < keyPoints.size(); i++) {
                keyPoints.get(i).coordinate = new PointF(points[i * 2] * scaleFactor, points[i * 2 + 1] * scaleFactor);
            }

            if (verbose) {
                String resultHead = String.format(Locale.ENGLISH,
                        "Analysis completed for frame: %04d\nKeyPoints:\n", frameIndex);
                StringBuilder builder = new StringBuilder(resultHead);

                for (KeyPoint keyPoint : keyPoints) {
                    builder.append("  ");
                    builder.append(keyPoint.toString());
                    builder.append('\n');
                }
                builder.append('\n');

                String resultMessage = builder.toString();
                Log.v(TAG, resultMessage);
            }

            int origWidth = (int) (bitmap.getWidth() * scaleFactor);
            int origHeight = (int) (bitmap.getHeight() * scaleFactor);

            boolean distracted = isDistracted(keyPoints, origWidth, origHeight);
            resultList.add(new InnerFrame(frameIndex, distracted, totalScore, keyPoints));
        }

        long endTime = System.currentTimeMillis();
        totalTime += endTime - startTime;

        //Log.d(TAG, "(Inner) Average time: " + (totalTime / (double)frameCnt));

        return resultList;
    }

    void setup(int width, int height) {
        /*if (!processorQueue.isEmpty()) {
            return;
        }*/
        int size = Math.min(height, width);
        cropRegion = initRectF(width, height);
/*
        try {
            for (int i = 0; i < THREAD_NUM; i++) {
                processorQueue.add(new ImageProcessor.Builder()
                        .add(new ResizeWithCropOrPadOp(size, size))
                        .add(new ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
                        .build());
            }
        } catch (Exception e) {
            ;
        }*/
    }

    /**
     * Defines the default crop region.
     * The function provides the initial crop region (pads the full image from both
     * sides to make it a square image) when the algorithm cannot reliably determine
     * the crop region from the previous frame.
     */
    private RectF initRectF(int imageWidth, int imageHeight) {
        float xMin;
        float yMin;
        float width;
        float height;

        if (imageWidth > imageHeight) {
            width = 1f;
            height = imageWidth / (float) imageHeight;
            xMin = 0f;
            yMin = (imageHeight / 2f - imageWidth / 2f) / imageHeight;
        } else {
            height = 1f;
            width = imageHeight / (float) imageWidth;
            yMin = 0f;
            xMin = (imageWidth / 2f - imageHeight / 2f) / imageWidth;
        }
        return new RectF(xMin, yMin, xMin + width, yMin + height);
    }

    /**
     * Looking down, looking back (not reversing), drinking, eating, using a phone
     * Fairly basic, not very sophisticated
     */
    private boolean isDistracted(List<KeyPoint> keyPoints, int imageWidth, int imageHeight) {
        Map<BodyPart, KeyPoint> keyDict = keyPoints.stream().collect(Collectors.toMap(k -> k.bodyPart, k -> k));

        boolean handsOccupied = false;

        KeyPoint wristL = keyDict.get(BodyPart.LEFT_WRIST);
        KeyPoint wristR = keyDict.get(BodyPart.RIGHT_WRIST);

        if (wristL != null && wristL.score >= MIN_SCORE) {
            handsOccupied = areHandsOccupied(wristL, imageHeight);
        }
        if (wristR != null && wristR.score >= MIN_SCORE) {
            handsOccupied = handsOccupied || areHandsOccupied(wristR, imageHeight);
        }

        KeyPoint eyeL = keyDict.get(BodyPart.LEFT_EYE);
        KeyPoint eyeR = keyDict.get(BodyPart.RIGHT_EYE);
        KeyPoint earL = keyDict.get(BodyPart.LEFT_EAR);
        KeyPoint earR = keyDict.get(BodyPart.RIGHT_EAR);

        boolean eyesOccupied = areEyesOccupied(eyeL, earL) || areEyesOccupied(eyeR, earR);

        return handsOccupied || eyesOccupied;
    }

    /**
     * If wrists are above 1/4 video height, then they aren't on the steering wheel and the driver is likely occupied
     * with something such as drinking or talking on the phone
     */
    private boolean areHandsOccupied(KeyPoint wrist, int imageHeight) {
        if (wrist == null) {
            return false;
        }
        if (!(wrist.bodyPart.equals(BodyPart.LEFT_WRIST) || wrist.bodyPart.equals(BodyPart.RIGHT_WRIST))) {
            Log.w(TAG, "Passed incorrect body part to areHandsOccupied");
            return false;
        }
        // Y coordinates are top-down, not bottom-up
        return wrist.coordinate.y < (imageHeight * 0.75);
    }

    /**
     * When looking straight ahead (watching the road), the eyes are positioned above the ears
     * When looking down (such as glancing at a phone), the eyes are vertically closer to the ears
     */
    private boolean areEyesOccupied(KeyPoint eye, KeyPoint ear) {
        if (eye == null || ear == null) {
            return false;
        }
        if (!(eye.bodyPart.equals(BodyPart.LEFT_EYE) || eye.bodyPart.equals(BodyPart.RIGHT_EYE)) ||
                !(ear.bodyPart.equals(BodyPart.LEFT_EAR) || ear.bodyPart.equals(BodyPart.RIGHT_EAR))) {
            Log.w(TAG, "Passed incorrect body part to areEyesOccupied");
            return false;
        }

        double dist = ear.coordinate.y - eye.coordinate.y;
        double threshold = ear.coordinate.y / 20.0;

        return dist < threshold;
    }

    float getScaleFactor(int width) {
        return 1.0f; // width / (float) inputWidth;
    }

    public void printParameters() {
        StringJoiner paramMessage = new StringJoiner("\n  ");
        paramMessage.add("Inner analysis parameters:");
        paramMessage.add(String.format("MIN_SCORE: %s", MIN_SCORE));
        paramMessage.add(String.format("TensorFlow Threads: %s", TF_THREAD_NUM));
        paramMessage.add(String.format("Analysis Threads: %s", THREAD_NUM));

        Log.i(I_TAG, paramMessage.toString());
    }
}
