package haofanfang.it.bjfu;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.AsyncTask;
import android.os.SystemClock;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * @author HaoFan Fang
 * @date 2019-12-24 21:06
 */

public class TfliteImageClassifier {
    /**
     * 输入维度：批大小为1，颜色通道数为3，图像尺寸224*224
     */
    private static final int DIM_BATCH_SIZE = 1;
    private static final int DIM_PIXEL_SIZE = 3;
    static final int DIM_IMG_SIZE_X = 224;
    static final int DIM_IMG_SIZE_Y = 224;

    private static final int IMAGE_MEAN = 128;
    private static final float IMAGE_STD = 128.0f;

    /**
     * Tensorflow Lite interpreter索引
     */
    private Interpreter tflite;

    /**
     * 类别标签索引
     */
    private List<String> labelList;

    /**
     * 存储图像数的ByteBuffer，作为tflite的输入
     */
    private ByteBuffer imgData = null;

    /**
     * 存储每一类别probability的数组，作为tflite的输入，返回后会有结果
     */
    private float[][] labelProbArray = null;

    /**
     * Assets存储的模型名
     */
    private static final String MODEL_PATH = "optimized_graph.lite";

    /**
     * 识别结果最大显示数
     */
    private static final int RESULTS_TO_SHOW = 3;

    /**
     * 预先分配图像数据存储缓冲区
     */
    private int[] intValues = new int[DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y];


    private PriorityQueue<Map.Entry<String, Float>> sortedLabels =
           new PriorityQueue<>(
                   RESULTS_TO_SHOW,
                   new Comparator<Map.Entry<String, Float>>() {
                       @Override
                       public int compare(Map.Entry<String, Float> o1, Map.Entry<String, Float> o2) {
                           return (o1.getValue().compareTo(o2.getValue()));
                       }
                   }
           );

    /**
     * 初始化ImageClassifier
     */
    TfliteImageClassifier(Activity activity) throws IOException {
        tflite = new Interpreter(loadModelFile(activity));
        labelList = loadLabelList(activity);
        imgData =
                ByteBuffer.allocateDirect(
                        4 * DIM_BATCH_SIZE * DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y * DIM_PIXEL_SIZE
                );
        imgData.order(ByteOrder.nativeOrder());
        labelProbArray = new float[1][labelList.size()];
    }

    /**
     * 对一张图像分类
     */
    String classifyFrame(Bitmap bitmap)  {
        if (tflite == null) {
            return "Uninitialized Classifier";
        }
        convertBitmapToByteBuffer(bitmap);

        long startTime = SystemClock.uptimeMillis();
        tflite.run(imgData, labelProbArray);
        long endTime = SystemClock.uptimeMillis();
        String textToShow = printTopKLabels();
        textToShow = Long.toString(endTime - startTime) + "ms" + textToShow;
        close();
        return textToShow;
    }

    /**
     * 从Assets中读取类别标签
     */
    private List<String> loadLabelList(Activity activity)throws IOException {
        List<String> labelList = new ArrayList<String>();
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(activity.getAssets().open(LABEL_PATH)));
        String line;
        while ((line = reader.readLine()) != null) {
            labelList.add(line);
        }
        reader.close();
        return labelList;
    }

    /**
     * Memory-map 将模型映射到内存
     */
    private MappedByteBuffer loadModelFile(Activity activity) throws IOException {
        AssetFileDescriptor fileDescriptor = activity.getAssets().openFd(MODEL_PATH);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    /**
     * 将Bitmap图像resize之后，写入ByteBuffer
     */
    private void convertBitmapToByteBuffer(Bitmap bitmap) {
        if (imgData == null) {
            return;
        }
        imgData.rewind();
        float scaleX = (float) DIM_IMG_SIZE_X / bitmap.getWidth();
        float scaleY = (float) DIM_IMG_SIZE_Y / bitmap.getHeight();
        Matrix matrix = new Matrix();
        matrix.postScale(scaleX, scaleY);
        Bitmap tbitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        tbitmap.getPixels(intValues, 0,tbitmap.getWidth(), 0, 0, tbitmap.getWidth(), tbitmap.getHeight());

        int pixel = 0;
        long startTime = SystemClock.uptimeMillis();
        for (int i = 0; i < DIM_IMG_SIZE_X; ++i) {
            for (int j = 0; j < DIM_IMG_SIZE_Y; ++j) {
                final int val = intValues[pixel++];
                imgData.putFloat((((val >> 16) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                imgData.putFloat((((val >> 8) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                imgData.putFloat((((val) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
            }
        }
        long endTime = SystemClock.uptimeMillis();


    }

    /**
     * 输出可能性最高的K个类别
     */
    private String printTopKLabels() {
        for (int i = 0; i < labelList.size(); ++i) {
            sortedLabels.add(
                    new AbstractMap.SimpleEntry<>(labelList.get(i), labelProbArray[0][i]));
            if (sortedLabels.size() > RESULTS_TO_SHOW) {
                sortedLabels.poll();
            }
        }
        String textToShow = "";
        final int size = sortedLabels.size();
        for (int i = 0; i < size; ++i) {
            Map.Entry<String, Float> label = sortedLabels.poll();
            textToShow = String.format("%s%s", String.format("%s:%4.2f", label.getKey(), label.getValue()), textToShow);
        }
        return textToShow;
    }

    /**
     * 本地识别异步进程
     */
    private class LocalAsyncTask extends AsyncTask<Bitmap, Integer, String> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected String doInBackground(Bitmap... bitmaps) {
            String result = null;
            try {
                result = new TfliteImageClassifier(MainActivity).classifyFrame(bitmaps[0]);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }


        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);
        }


        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);
            resultTV.setText(result);
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
        }


    }



}
