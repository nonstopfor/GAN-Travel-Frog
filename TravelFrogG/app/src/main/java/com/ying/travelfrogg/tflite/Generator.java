package com.ying.travelfrogg.tflite;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.nnapi.NnApiDelegate;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.MappedByteBuffer;

public abstract class Generator {
    /** The model type used for generation. */
    public enum Model {
        FLOAT,
        QUANTIZED
    }

    /** The runtime device type used for executing generation. */
    public enum Device {
        CPU,
        NNAPI,
        GPU
    }

    /** The loaded TensorFlow Lite model. */
    private MappedByteBuffer tfliteModel;

    private GpuDelegate gpuDelegate = null;         // if device GPU
    private NnApiDelegate nnApiDelegate = null;     // if device NNAPI

    /** An instance of the driver class to run model inference with Tensorflow Lite. */
    protected Interpreter tflite;

    /** Options for configuring the Interpreter. */
    private final Interpreter.Options tfliteOptions = new Interpreter.Options();

    private final int imageSizeX;
    private final int imageSizeY;

    /** Input image TensorBuffer. */
    private TensorImage inputImageBuffer;

    /** Output image TensorBuffer. */
    private TensorImage outputImageBuffer;

    private TensorBuffer outputBuffer;


    /** Gets the name of the model file stored in Assets. */
    protected abstract String getModelPath();

    /**
     * Creates a generator with the provided configuration.
     *
     * @param activity The current Activity.
     * @param model The model to use for generation.
     * @param device The device to use for generation.
     * @param numThreads The number of threads to use for generation.
     * @return A generator with the desired configuration.
     */
    public static Generator create(Activity activity, Model model, Device device, int numThreads)
            throws IOException {
        if (model == Model.QUANTIZED) {
            return new GeneratorQuantized(activity, device, numThreads);
        } else {
            return new GeneratorFloat(activity, device, numThreads);
        }
    }

    /** Initializes a {@code Generator}. */
    protected Generator(Activity activity, Device device, int numThreads) throws IOException {

        /** Setup TF Lite */
        tfliteModel = FileUtil.loadMappedFile(activity, getModelPath());

        // if not CPU, use delegate
        switch (device) {
            case NNAPI:
                nnApiDelegate = new NnApiDelegate();
                tfliteOptions.addDelegate(nnApiDelegate);
                break;
            case GPU:
                gpuDelegate = new GpuDelegate();
                tfliteOptions.addDelegate(gpuDelegate);
                break;
            case CPU:
                break;
        }

        tfliteOptions.setNumThreads(numThreads);
        tflite = new Interpreter(tfliteModel, tfliteOptions);

        /** Set input image buffer */
        int inputTensorIndex = 0;
        int[] inputShape
                = tflite.getInputTensor(inputTensorIndex).shape(); // {1, height, width, 3}
        imageSizeY = inputShape[1];
        imageSizeX = inputShape[2];
        DataType inputDataType = tflite.getInputTensor(inputTensorIndex).dataType();
        inputImageBuffer = new TensorImage(inputDataType);

        /** Set output image buffer */
        int outputTensorIndex = 0;

        int[] outputShape =
                tflite.getOutputTensor(outputTensorIndex).shape();

        DataType outputDataType = tflite.getOutputTensor(outputTensorIndex).dataType();
        outputImageBuffer = new TensorImage(outputDataType);

        outputBuffer = TensorBuffer.createFixedSize(outputShape, outputDataType);
    }

    /** Loads input image. */
    private TensorImage loadImage(final Bitmap bitmap) {
        // Loads bitmap into a TensorImage.
        inputImageBuffer.load(bitmap);

        // Creates processor for the TensorImage.
        int cropSize = Math.min(bitmap.getWidth(), bitmap.getHeight());
        ImageProcessor imageProcessor =
                new ImageProcessor.Builder()
                        .add(new ResizeWithCropOrPadOp(cropSize, cropSize))
                        .add(new ResizeOp(imageSizeX, imageSizeY, ResizeOp.ResizeMethod.NEAREST_NEIGHBOR))
                        .build();

        return imageProcessor.process(inputImageBuffer);
    }

    public Bitmap generateImage(Bitmap drawing) {
        // load input bitmap
        inputImageBuffer = loadImage(drawing);

        // apply generator model for inference
        Trace.beginSection("runInference");
        long startTimeForReference = SystemClock.uptimeMillis();
        tflite.run(inputImageBuffer.getBuffer(), outputBuffer.getBuffer());

        byte[] arr = outputBuffer.getBuffer().array();
        for (int i = 0; i < 10; i++) {
            System.out.print(arr[i] + " ");
        }
        System.out.println(" ");

        long endTimeForReference = SystemClock.uptimeMillis();
        Trace.endSection();
        Log.d("PIX2PIX", "spent " + (endTimeForReference - startTimeForReference) + "ms");

        // return generated bitmap
        Bitmap generated;
        int[] shape = outputBuffer.getShape();
        int h = shape[0];
        int w = shape[1];
        generated = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

        // TODO: Find a way to avoid creating multiple intermediate buffers every time.
        int[] intValues = new int[w * h];
        int[] rgbValues = outputBuffer.getIntArray();
        for (int i = 0, j = 0; i < intValues.length; i++) {
            byte r = (byte) rgbValues[j++];
            byte g = (byte) rgbValues[j++];
            byte b = (byte) rgbValues[j++];
            intValues[i] = ((r << 16) | (g << 8) | b);
//            if (i % 50 == 0) System.out.println(i + " " + intValues[i]);
        }
        Log.d("BITMAP", "finish generating");
        generated.setPixels(intValues, 0, w, 0, 0, w, h);

        return generated;
    }
}
