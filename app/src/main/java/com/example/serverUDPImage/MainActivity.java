package com.example.serverUDPImage;

import static android.os.Build.VERSION.SDK_INT;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private static final int REQUEST_MICROPHONE = 100;
    Streaming.CommunicationReceiveThread communicationReceiveThread;
    //Streaming.TestSendDataCommunicationThread communicationSendThread;
    Thread communicationReceiveThreadHandler = null;
    //Thread communicationSendThreadHandler = null;
    private LinearLayout msgList;
    private int greenColor;
    private EditText edMessage;

    //Audio
    AudioRecord record = null;
    AudioTrack track = null;

    boolean isRecording;
    int sampleRate = 44100;

    Button startRecord, stopRecord, playRecord = null;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2296) {
            if (SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    // perform action when allow permission success
                } else {
                    Toast.makeText(this, "Allow permission for storage access!", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //Setup.requestPermission(MainActivity.this);
        setContentView(R.layout.activity_main);
        setTitle("Server");
        greenColor = ContextCompat.getColor(this, R.color.green);
        //msgList = findViewById(R.id.msgList);
        edMessage = findViewById(R.id.edMessage);
        verifyStoragePermissions(this);
        //setContentView(R.layout.activity_main);
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        InetAddress localIPAddress = null;
        try {
            localIPAddress = InetAddress.getByName(Formatter.formatIpAddress(wifiManager.getConnectionInfo().getIpAddress()));
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        ArrayList<View> listViewObjects = new ArrayList<>();
        listViewObjects.add(findViewById(R.id.imageView));
        listViewObjects.add(findViewById(R.id.textViewFPS));
        listViewObjects.add(findViewById(R.id.textViewKbps));
        listViewObjects.add(findViewById(R.id.textViewPER));
        listViewObjects.add(findViewById(R.id.textViewKbpsSucceed));
        communicationReceiveThread = (new Streaming()).new CommunicationReceiveThread(this, listViewObjects);

        //communicationSendThread = (new Streaming()).new TestSendDataCommunicationThread(findViewById(R.id.imageView), localIPAddress, this);

        //Audio
        setVolumeControlStream(AudioManager.MODE_IN_COMMUNICATION);
        startRecord = (Button) findViewById(R.id.start_recording);
        stopRecord = (Button) findViewById(R.id.stop_recording);
        playRecord = (Button) findViewById(R.id.play_recording);
        startRecord.setOnClickListener(new StartRecordListener());
        stopRecord.setOnClickListener(new StopRecordListener());
        playRecord.setOnClickListener(new PlayRecordListener());

        stopRecord.setEnabled(false);

    }

    @Override
    public void onClick(View view) {

        if (view.getId() == R.id.start_server) {
            //msgList.removeAllViews();
            //Helpers.showMessage("Server Started.", Color.BLACK,msgList, this);
            this.communicationReceiveThreadHandler = new Thread(communicationReceiveThread);
            //this.communicationSendThreadHandler = new Thread(communicationSendThread);
            this.communicationReceiveThreadHandler.start();
            //this.communicationSendThreadHandler.start();
            return;
        }

        if (view.getId() == R.id.send_data) {
            String msg = edMessage.getText().toString().trim();
            //Helpers.showMessage("Server : " + msg, Color.BLUE, msgList, this);
            communicationReceiveThread.sendMessage(msg);
        }

    }

    // Storage Permissions
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };


    public static void verifyStoragePermissions(Activity activity) {
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (null != communicationReceiveThreadHandler) {
            communicationReceiveThread.sendMessage("Disconnect");
            communicationReceiveThreadHandler.interrupt();
            communicationReceiveThreadHandler = null;
        }
    }

    private void startRecord() {
        File recordFile = new File(Environment.getExternalStorageDirectory(), "Record.pcm");
        try {
            recordFile.createNewFile();

            OutputStream outputStream = new FileOutputStream(recordFile);
            BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream);
            DataOutputStream dataOutputStream = new DataOutputStream(bufferedOutputStream);

            int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

            short[] audioData = new short[minBufferSize];

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.

                ActivityCompat.requestPermissions(getActivity(),
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_MICROPHONE);
                return;
            }

            record = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize);
            record.startRecording();

            while (isRecording)
            {
                int numberOfShort = record.read(audioData, 0, minBufferSize);
                for (int i = 0; i < numberOfShort; i++)
                {
                    dataOutputStream.writeShort(audioData[i]);
                }
            }
            record.stop();
            dataOutputStream.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private Activity getActivity() {
        return MainActivity.this;
    }

    public void playRecord()
    {
        File recordFile = new File(Environment.getExternalStorageDirectory(), "Record.pcm");

        int shortSizeInBytes = Short.SIZE / Byte.SIZE;
        int bufferSizeInBytes = (int) (recordFile.length() / shortSizeInBytes);
        short[] audioData = new short[bufferSizeInBytes];
        try
        {
            InputStream inputStream = new FileInputStream(recordFile);
            BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
            DataInputStream dataInputStream = new DataInputStream(bufferedInputStream);

            int i = 0;
            while (dataInputStream.available() > 0)
            {
                audioData[i] = dataInputStream.readShort();
                i++;
            }

            dataInputStream.close();

            track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeInBytes, AudioTrack.MODE_STREAM);

            track.play();
            track.write(audioData, 0, bufferSizeInBytes);
        }
        catch (FileNotFoundException e)
        {
            e.printStackTrace();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }


    public class StartRecordListener implements View.OnClickListener
    {
        @Override
        public void onClick(View v)
        {
            Thread recordThread = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    isRecording = true;
                    MainActivity.this.startRecord();
                }
            });
            recordThread.start();
            startRecord.setEnabled(false);
            stopRecord.setEnabled(true);
        }
    }

    public class StopRecordListener implements View.OnClickListener
    {
        @Override
        public void onClick(View v)
        {
            isRecording = false;
            startRecord.setEnabled(true);
            stopRecord.setEnabled(false);
        }
    }

    public class PlayRecordListener implements View.OnClickListener
    {
        @Override
        public void onClick(View v)
        {
            MainActivity.this.playRecord();
        }
    }
}