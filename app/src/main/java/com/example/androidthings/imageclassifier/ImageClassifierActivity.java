/*
 * Copyright 2017 The Android Things Samples Authors.
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
package com.example.androidthings.imageclassifier;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.alibaba.sdk.android.oss.ClientException;
import com.alibaba.sdk.android.oss.OSSClient;
import com.alibaba.sdk.android.oss.ServiceException;
import com.alibaba.sdk.android.oss.common.auth.OSSAuthCredentialsProvider;
import com.alibaba.sdk.android.oss.common.auth.OSSCredentialProvider;
import com.alibaba.sdk.android.oss.common.auth.OSSPlainTextAKSKCredentialProvider;
import com.alibaba.sdk.android.oss.model.PutObjectRequest;
import com.alibaba.sdk.android.oss.model.PutObjectResult;
import com.example.androidthings.imageclassifier.classifier.Recognition;
import com.example.androidthings.imageclassifier.classifier.TensorFlowImageClassifier;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.button.ButtonInputDriver;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManager;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.example.androidthings.imageclassifier.OSSConstant.ACCESS_KEY_ID;
import static com.example.androidthings.imageclassifier.OSSConstant.ACCESS_KEY_SECRET;
import static com.example.androidthings.imageclassifier.OSSConstant.BUCKET_NAME;
import static com.example.androidthings.imageclassifier.OSSConstant.ENDPOINT;
import static com.example.androidthings.imageclassifier.OSSConstant.FOLDER;

public class ImageClassifierActivity extends Activity implements ImageReader.OnImageAvailableListener {
    private static final String TAG = "ImageClassifierActivity";

    private static final int PREVIEW_IMAGE_WIDTH = 640;
    private static final int PREVIEW_IMAGE_HEIGHT = 480;
    private static final int TF_INPUT_IMAGE_WIDTH = 224;
    private static final int TF_INPUT_IMAGE_HEIGHT = 224;

    /* Key code used by GPIO button to trigger image capture */
    private static final int SHUTTER_KEYCODE = KeyEvent.KEYCODE_CAMERA;

    private ImagePreprocessor mImagePreprocessor;
    private CameraHandler mCameraHandler;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private ImageView mImage;
    private TextView mResultText;

    private AtomicBoolean mReady = new AtomicBoolean(false);
    private ButtonInputDriver mButtonDriver;
    //private Gpio mReadyLED;

    // #######################################3
    //推荐使用OSSAuthCredentialsProvider。token过期可以及时更新
    private OSSCredentialProvider credentialProvider = null;
    private OSSClient client = null;
    public static String img_url = null;

    // Mqtt部分
    MqttHelper mqttHelper;
    final String publishMessage = "Hello World!";
    private String msg = null;  // 保存返回后信息的字符串


    // 4.27号添加,Mqtt部分
    public static MqttAndroidClient mqttIoTClient;
    private MqttConnectOptions options;
    // 4.27号添加End

    // 4.28号添加等待进度条
    // 等待进度框
    private ProgressDialog progressDialog;
    private Bitmap bitmap;  // 设置成为全局变量

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_camera);
        mImage = findViewById(R.id.imageView);
        mResultText = findViewById(R.id.resultText);

        msg = null;

        // ####################################################################
        credentialProvider = new OSSPlainTextAKSKCredentialProvider(ACCESS_KEY_ID, ACCESS_KEY_SECRET);
        client = new OSSClient(getApplicationContext(), ENDPOINT, credentialProvider);

        //4.27号添加Mqtt部分
        mqttHelper = new MqttHelper();
        String clientId = MqttClient.generateClientId();
        mqttIoTClient = new MqttAndroidClient(this.getApplicationContext(), mqttHelper.serverUri, clientId);
        options = new MqttConnectOptions();
        options.setUserName(mqttHelper.username);
        options.setPassword(mqttHelper.password.toCharArray());


        // 4.27号添加---子线程,用于实现Mqtt订阅控制摄像头的功能
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    IMqttToken token = mqttIoTClient.connect(options);
                    token.setActionCallback(new IMqttActionListener() {
                        @Override
                        public void onSuccess(IMqttToken asyncActionToken) {
                            // We are connected
                            Toast.makeText(ImageClassifierActivity.this, "Connected!", Toast.LENGTH_LONG).show();
                            setSubscription("control_camera_iot");
                        }

                        @Override
                        public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                            // Something went wrong e.g. connection timeout or firewall problems
                            Toast.makeText(ImageClassifierActivity.this, "Connection Failed!", Toast.LENGTH_LONG).show();

                        }
                    });


                    // 下面那个是设置回调函数
                    mqttIoTClient.setCallback(new MqttCallback() {
                        @Override
                        public void connectionLost(Throwable cause) {

                        }

                        // 接收到消息
                        @Override
                        public void messageArrived(String topic, MqttMessage message) throws Exception {
                            System.out.println("啦啦啦啦啦！！这里出现了说明线程可以运行！！！！");
                            System.out.println("看看是哪个topic的: "+topic);
                            if(topic.equals("control_camera_iot")) {
                                // 接收到的消息
                                String receivedMsg = new String(message.getPayload());
                                if (receivedMsg.equals("capturePic")) {
                                    // 开始捕获图像
                                    startImageCapture();
                                }
                            }else if(topic.equals("iot_data")){
                                String receivedMsg = new String(message.getPayload());
                                System.out.println("看看这个线程里面收到的消息是什么: "+receivedMsg);
                                msg = receivedMsg;
                                System.out.println("那msg呢: "+msg);
                            }
                        }

                        @Override
                        public void deliveryComplete(IMqttDeliveryToken token) {

                        }
                    });
                } catch (MqttException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        // 先暂时把它注释一下
        init();
    }

    // 订阅某个topic
    private void setSubscription(String topic_name){
        try {
            mqttIoTClient.subscribe(topic_name, 0);
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    private void init() {
        mBackgroundThread = new HandlerThread("BackgroundThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        mBackgroundHandler.post(mInitializeOnBackground);

        //startMqtt();
        /*mqttHelper = new MqttHelper();*/
    }

    private Runnable mInitializeOnBackground = new Runnable() {
        @Override
        public void run() {
            mImagePreprocessor = new ImagePreprocessor(PREVIEW_IMAGE_WIDTH, PREVIEW_IMAGE_HEIGHT,
                    TF_INPUT_IMAGE_WIDTH, TF_INPUT_IMAGE_HEIGHT);

            mCameraHandler = CameraHandler.getInstance();
            mCameraHandler.initializeCamera(ImageClassifierActivity.this,
                    PREVIEW_IMAGE_WIDTH, PREVIEW_IMAGE_HEIGHT, mBackgroundHandler,
                    ImageClassifierActivity.this);

            setReady(true);
        }
    };

    private Runnable mBackgroundClickHandler = new Runnable() {
        @Override
        public void run() {
            mCameraHandler.takePicture();
        }
    };

    private UtteranceProgressListener utteranceListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            setReady(false);
        }

        @Override
        public void onDone(String utteranceId) {
            setReady(true);
        }

        @Override
        public void onError(String utteranceId) {
            setReady(true);
        }
    };

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Log.d(TAG, "Received key up: " + keyCode);
        if (keyCode == SHUTTER_KEYCODE) {
            startImageCapture();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Invoked when the user taps on the UI from a touch-enabled display
     */
    public void onShutterClick(View view) {
        Log.d(TAG, "Received screen tap");
        startImageCapture();
    }

    /**
     * Verify and initiate a new image capture
     */
    private void startImageCapture() {
        Log.d(TAG, "Ready for another capture? " + mReady.get());
        if (mReady.get()) {
            setReady(false);
            mResultText.setText("Hold on...");
            mBackgroundHandler.post(mBackgroundClickHandler);
        } else {
            Log.i(TAG, "Sorry, processing hasn't finished. Try again in a few seconds");
        }
    }

    /**
     * Mark the system as ready for a new image capture
     */
    private void setReady(boolean ready) {
        mReady.set(ready);
    }

    // 这部分代码是捕捉按键按下的代码。当按下按键时，摄头开始捕捉数据。
    // 把摄像头拍摄的数据转成 Bitmap 文件之后
    // 再调用TensorFlow 来处理图像
    @Override
    public void onImageAvailable(ImageReader reader) {

        //下面就是获取图片的代码
        try (Image image = reader.acquireNextImage()) {
            // 把image转化为bitmap
            bitmap = mImagePreprocessor.preprocessImage(image);

            //#######################################################################
            // 下面是我写的内容
            // 把bitmap转化为byte数组
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            // 构造上传请求
            // 这里的objectKey其实就是服务器上的路径，即目录+文件名(相当于是自己定义)
            // 上传的操作都要写在try-catch里面
            int flag = -1;
            try {
                String object_key = FOLDER + "camera_img.jpg";
                PutObjectRequest put = new PutObjectRequest(BUCKET_NAME, object_key, byteArray);

                PutObjectResult putResult = client.putObject(put);
                Log.d("PutObject", "UploadSuccess");
                Log.d("ETag", putResult.getETag());
                Log.d("RequestId", putResult.getRequestId());

                // ###########################################
                // 保存上传图片的文件路径
                img_url = "https://tf-img-classifier.oss-cn-shanghai.aliyuncs.com/camera_img.jpg";
                System.out.println("---------------->OSS上传成功!");

                flag = 1;
            } catch (ClientException e) {
                // 本地异常如网络异常等
                e.printStackTrace();
                System.out.println("---------------->失败!");
            } catch (ServiceException e) {
                // 服务异常
                Log.e("RequestId", e.getRequestId());
                Log.e("ErrorCode", e.getErrorCode());
                Log.e("HostId", e.getHostId());
                Log.e("RawMessage", e.getRawMessage());
                System.out.println("---------------->异常!");
            }
            if(flag == 1){
                // ##################################################
                // 4.1号添加
                // Mqtt发布到broker中去
                // 4.28号再次进行代码的整改Publish!
                try {
                    // 清空msg
                    msg = null;

                    mqttIoTClient.publish(mqttHelper.publishTopic, img_url.getBytes(), 0, false);
                    Toast.makeText(ImageClassifierActivity.this, "Publish Successful!", Toast.LENGTH_LONG).show();

                    // 设置订阅另一个topic的操作!!!(不知道这样能不能行...)
                    setSubscription(mqttHelper.subscriptionTopic);

                    // 加上等待进度条
                    progressDialog = new ProgressDialog(ImageClassifierActivity.this);
                    progressDialog.setTitle("Predict");
                    progressDialog.setMessage("Loading...Plz wait...");
                    progressDialog.setCancelable(false);
                    progressDialog.show();

                    /* 开启一个新线程，在新线程里执行耗时的方法 */
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            spandTimeMethod();// 耗时的方法
                            handler.sendEmptyMessage(0);// 执行耗时的方法之后发送消给handler
                        }

                    }).start();
                } catch (MqttException e) {
                    e.printStackTrace();
                }
            }
        }


    }


    // 4.28加: 耗时方法
    private void spandTimeMethod(){
        while(msg == null || msg.isEmpty()){
            System.out.println("xxxxxxxxx------->: "+msg);
            if(msg!=null) break;
        }

        //===================================
        // 我把它移到这里来了,本来应该在result上方的
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mImage.setImageBitmap(bitmap);
            }
        });

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (msg == null || msg.isEmpty()) {
                    mResultText.setText("I don't understand what I see");
                } else {
                    System.out.println("这里msg执行了吗???");
                    System.out.println(msg);
                    String third_key=null, second_key=null, first_key="Unknown Animal";
                    try {
                        JSONObject obj = new JSONObject(msg);
                        double tiger = obj.getDouble("tiger");
                        double dog = obj.getDouble("dog");
                        double cat = obj.getDouble("cat");
                        double bird = obj.getDouble("bird");
                        double fish = obj.getDouble("fish");
                        double[] li = new double[5];
                        li[0] = tiger;
                        li[1] = dog;
                        li[2] = cat;
                        li[3] = bird;
                        li[4] = fish;
                        Arrays.sort(li);
                        double third_prob=li[2], second_prob=li[3], first_prob=li[4];

                        if(first_prob == tiger) first_key = "Tiger: "+(first_prob*100.0)+"%";
                        if(first_prob == dog) first_key = "Dog: "+(first_prob*100.0)+"%";
                        if(first_prob == cat) first_key = "Cat: "+(first_prob*100.0)+"%";
                        if(first_prob == bird) first_key = "Bird: "+(first_prob*100.0)+"%";
                        if(first_prob == fish) first_key = "Fish: "+(first_prob*100.0)+"%";
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    mResultText.setText(first_key.toString());
                }
            }
        });

        // if theres no TTS, we don't need to wait until the utterance is spoken, so we set
        // to ready right away.
        setReady(true);
    }

    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {// handler接收到消息后就会执行此方法
            progressDialog.dismiss();// 关闭ProgressDialog
        }
    };
    // 4.28加：耗时方法结束

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (mBackgroundThread != null) mBackgroundThread.quit();
        } catch (Throwable t) {
            // close quietly
        }
        mBackgroundThread = null;
        mBackgroundHandler = null;

        try {
            if (mCameraHandler != null) mCameraHandler.shutDown();
        } catch (Throwable t) {
            // close quietly
        }
        /*try {
            if (mTensorFlowClassifier != null) mTensorFlowClassifier.destroyClassifier();
        } catch (Throwable t) {
            // close quietly
        }*/
        try {
            if (mButtonDriver != null) mButtonDriver.close();
        } catch (Throwable t) {
            // close quietly
        }
    }

}
