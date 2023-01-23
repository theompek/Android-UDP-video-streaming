package com.example.serverUDPImage;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;

public class Streaming {
    int packetsDataLength = 920;
    int datagramPacketLength = 1024;
    int maxImagesStored = 100;
    byte headerLen = 30;
    int reservedBytesNum = 4;
    int NumOfChkSums = 8;
    int confirmationMessageLen = 8;
    String respTimeMsg = "RESPONSE_TIME_REQUEST";
    int customMsgFromServerLen = 101;
    int[] checkSumPositions = {headerLen,256,512,768,packetsDataLength+headerLen};
    byte defaultResendPacketCode = (byte) 0b11111111;

    public class CommunicationReceiveThread implements Runnable {
        public DatagramSocket phoneSocket;
        public int phonePort;

        private byte[] buf = new byte[datagramPacketLength];
        private DatagramPacket phoneDpReceive = new DatagramPacket(buf, buf.length);

        Queue<FrameReceivedObject> imagesQueue = new LinkedList<>();
        int lastCompletedFrameInQueue = -1;
        FrameReceivedObject[] FramesBuffer = new FrameReceivedObject[maxImagesStored];

        byte[] currentPacketData = new byte[10000];
        byte[] datagramData = new byte[1];
        boolean searchFrame = false;
        int frStart;
        byte[] header;
        int currentPacketLength = 0;
        byte packetType;
        static final byte imageType = 0;
        static final byte audioType = 1;
        byte packetResend;
        int packetDataSize;
        int frameId;
        int packetsNumber;
        int packetId;
        int frameSize;
        int localFrameId;
        int prevLocalFrameId = 0;
        int receivedCheckSum[] = new int[NumOfChkSums];
        byte checkSums[] = new byte[NumOfChkSums];
        boolean currentFrameIsBroken = false;
        ImageView imageView;
        Activity mainActivity;
        TextView textViewFPS;
        TextView textViewKbps;
        TextView textViewPER;
        TextView textViewKbpsSucceed;
        InetAddress esp32Ip;
        int esp32Port;
        //Time measure for timePerFrameSum calculation
        int timePerFrameSum = 0;
        float PER =0;//Packets Error Rate, how many packet received have error divided by the total packets received
        int PErrors = 0; //Number of packets with errors
        int PCount = 1; //Packets Received
        boolean displayStat_bo = false;
        float Kbps = 0;
        float KbpsSucceed = 0;
        int framesCount = 0;
        long startTime = System.currentTimeMillis();
        long timePrevFrame = System.currentTimeMillis();
        int ackBuffLen = 1+4+1+1;  // packetId(1), frameId(4), localFrameId(1), errorPart(1)
        byte ACKPacketBf[] = new byte[ackBuffLen];
        byte errorPart = 0;
        boolean packetIsCorrupted = false;
        boolean restoreCorruptedPacket = false;
        int corruptedPacketLength = 0;
        int responseTimePhone;

        //Resend data parts positions
        boolean[] pos = {false, false, false, false};

        //The thread that display the images with a static rate (stable fps)
        Thread displayImagesThread;

        //-------------------------------------------CLIENT----------------------------------------
        public CommunicationReceiveThread(Activity mainActivity, ArrayList<View> listViewObjects){
            try {
                phonePort = 8081;
                phoneSocket = new DatagramSocket(phonePort);
                esp32Port = 8000;
                esp32Ip = InetAddress.getByName("192.168.2.180");
                for(int i=0;i<maxImagesStored;i++){
                    FramesBuffer[i]=new FrameReceivedObject();
                }
            } catch (IOException e) {
                Log.e("Myti", "Exception in CommunicationReceiveThread : ", e);
            }
            this.imageView = (ImageView) listViewObjects.get(0);
            this.mainActivity = mainActivity;
            this.textViewFPS = (TextView) listViewObjects.get(1);
            this.textViewKbps = (TextView) listViewObjects.get(2);
            this.textViewPER = (TextView) listViewObjects.get(3);
            this.textViewKbpsSucceed = (TextView) listViewObjects.get(4);

        }

        /*public int findDelimiter(byte[] data, byte[] delimiter) {
            int i = headerLen;
            int j = 0;
            while (i < (data.length-delimiter.length)) {
                for (j = 0; j < delimiter.length; j++) {
                    if (i + j >= data.length) return -1;
                    if (data[i + j] != delimiter[j]) break;
                }
                if (j == delimiter.length) return i;
                i++;
            }
            return -1;
        }*/

        @SuppressLint("SetTextI18n")
        public void run() {
            //Start the images thread responsible to display the images in a specific rate of frames
            displayImagesThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    long prevTime=System.currentTimeMillis();
                    long timeInterval=20;    //msec
                    long waiTime=0;    //msec

                    //Idea: We can create a pid controller here to control the fps so to be stable
                    //We measure the queue size and the fps and we try to keep the fps in a stable number
                    //regarding with the size changes of the queue
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            displayImages();
                            waiTime = timeInterval - (System.currentTimeMillis()-prevTime);
                            if(waiTime>0) Thread.sleep(waiTime);
                            prevTime = System.currentTimeMillis();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
            displayImagesThread.start();

            while (!Thread.currentThread().isInterrupted()) {

                try {
                    // Initialize array
                    //Arrays.fill(datagramData, (byte) 0);
                    phoneSocket.receive(phoneDpReceive);
                    datagramData = phoneDpReceive.getData();
                    packetIsCorrupted = false;

                    header = Arrays.copyOfRange(datagramData, 0, headerLen);
                    //For image we send 0b00000000 and for audio 0b11111111, because of the udp bits corruption
                    //we count the number of 1 in the byte. 0, 1, 2, 3 number of 1 means image type
                    //and 4, 5, 6, 7 ,8 number of 1 means audio type.
                    frameId = ((header[4] & 0x00ff) << 24) | ((header[3] & 0x00ff) << 16) |
                            ((header[2] & 0x00ff) << 8) | (header[1] & 0xff);
                    frameSize = ((header[8] & 0x00ff) << 24) | ((header[7] & 0x00ff) << 16) |
                            ((header[6] & 0x00ff) << 8) | (header[5] & 0xff);
                    packetsNumber = header[9] & 0xff;
                    packetId = (header[10] & 0xff);
                    packetDataSize = ((header[14] & 0xff) << 8) | (header[13] & 0xff);  //Only the frame data of the packet, without header
                    localFrameId = (header[15] & 0xff);
                    packetType = Integer.bitCount(header[16]) > 3 ? audioType : imageType;
                    packetResend = (byte)(header[17] & 0xff);

                    if (packetType == imageType){
                        storeImages();
                    }else{
                        continue;
                    }


                } catch (IOException e) {
                    Log.d("Myti", "Exception server");
                    e.printStackTrace();
                }

            }
        }


        public void storeImages(){
            try {
                displayStatistics(mainActivity);
                displayStat_bo = false; // We can not display statistics if we are in the middle of a frame

                currentFrameIsBroken = false;
                corruptedPacketLength = 0;

                //Check the CheckSum to insure the integrity of the data
                for (int i = 0; i < NumOfChkSums; i++) {
                    //Get received checkSums
                    receivedCheckSum[i] = datagramData[headerLen - NumOfChkSums - reservedBytesNum + i];

                    //Set to zero the checkSum fields and calculate the checksum of the current packet
                    datagramData[headerLen - NumOfChkSums - reservedBytesNum + i] = 0;
                }

                restoreCorruptedPacket = false;
                //If we get a resend packet then we need to put the data in correct position
                if (packetResend != defaultResendPacketCode) {
                    byte[] tempData = new byte[datagramPacketLength];
                    System.arraycopy(FramesBuffer[localFrameId].buffer, packetId * packetsDataLength, tempData, 0, packetsDataLength);
                    //System.arraycopy(datagramData, 0, tempData, 0, headerLen);
                    int prevStep = 0;
                    int startPosFrame = 0;
                    int startPosBuff = 0;
                    int lengthData = 0;
                    int overallLength = 0;

                    for (int i = 0; i < pos.length; i++) {
                        pos[i] = (packetResend & (0b000000001 << i)) != 0;
                    }

                    for (int i = 0; i < pos.length; i++) {
                        if (pos[i]) {
                            prevStep = checkSumPositions[i] - checkSumPositions[0];
                            startPosFrame = prevStep;
                            startPosBuff = checkSumPositions[0] + overallLength;
                            lengthData = checkSumPositions[i + 1] - checkSumPositions[i];
                            lengthData = overallLength + lengthData > packetDataSize ? (packetDataSize - overallLength) : lengthData;
                            System.arraycopy(datagramData, startPosBuff, tempData, startPosFrame, lengthData);
                            overallLength += lengthData;
                        }
                    }


                    System.arraycopy(tempData, 0, currentPacketData, 0, packetsDataLength);
                    restoreCorruptedPacket = true;

                    int d = tempData.length;
                    //continue;
                }

                //checkSums = CalcCheckSums(datagramData, NumOfChkSums, headerLen + packetDataSize);
                checkSums = Fletcher16(datagramData, checkSumPositions, headerLen + packetDataSize);
                //checkSums = XOR_CheckSum(datagramData, checkSumPositions, headerLen + packetDataSize);

                //Calculate Packet Error Rate And errors in packet, we divide the received packet into 'NumOfChkSums' number of chunks
                PCount += NumOfChkSums;
                Kbps += packetDataSize + headerLen;
                errorPart = 0;
                for (int i = 0; i < NumOfChkSums / 2; i++) {
                    if (checkSums[2 * i] != receivedCheckSum[2 * i] || checkSums[2 * i + 1] != receivedCheckSum[2 * i + 1]) {
                        PErrors++;
                        currentFrameIsBroken = true;
                        errorPart = (byte) (errorPart | (1 << i));
                    }
                }

                //if (checkSums[0] != receivedCheckSum[0]) packetIsCorrupted=true;
                if (currentFrameIsBroken) packetIsCorrupted = true;

                if (packetIsCorrupted) {
                    //Send message back to the server (esp32 board)
                    ACKPacketBf[0] = header[10];    //packetId
                    ACKPacketBf[1] = header[1];     //frameId1
                    ACKPacketBf[2] = header[2];     //frameId2
                    ACKPacketBf[3] = header[3];     //frameId3
                    ACKPacketBf[4] = header[4];     //frameId4
                    ACKPacketBf[5] = header[15];    //localFrameId
                    ACKPacketBf[6] = errorPart;     //CheckSum part with error

                    DatagramPacket dp = new DatagramPacket(ACKPacketBf, ACKPacketBf.length, phoneDpReceive.getAddress(), phoneDpReceive.getPort());
                    phoneSocket.send(dp);

                    int lengthData = 0;
                    int overallLength = 0;
                    corruptedPacketLength = 0;
                    for (int i = 0; i < pos.length; i++) {
                        if ((errorPart & (0b000000001 << i)) != 0) {
                            lengthData = checkSumPositions[i + 1] - checkSumPositions[i];
                            corruptedPacketLength += overallLength + lengthData > packetDataSize ? (packetDataSize - overallLength) : lengthData;
                            overallLength += lengthData;
                        }
                    }
                }

                KbpsSucceed += packetDataSize + headerLen - corruptedPacketLength;

                    /*
                    if(packetDataSize < customMsgFromServerLen){
                        String msgFromServer = new String( Arrays.copyOfRange(datagramData, headerLen, headerLen+packetDataSize), Charset.forName("UTF-8"));
                        if(respTimeMsg.equals(msgFromServer)){
                            DatagramPacket dp = new DatagramPacket(ACKPacketBf, ACKPacketBf.length, phoneDpReceive.getAddress(), phoneDpReceive.getPort());
                            phoneSocket.send(dp);

                            continue;
                        }
                    }
                    */

                if (checkRequestForResponseTime()) return;

                if (localFrameId >= maxImagesStored || localFrameId < 0) {
                    //The other values from the header can be used so as the frame be restored, for example we
                    //can compare the values frameId or packetId or frameSize with the values of the store frameObject and find
                    //the right location of the packet, for the moment we just skip this step and we implement this in the future
                    return;
                }

                //If the current received packet is from an old frame and we have in the queue
                //a newer completed frame then we can skip this packet because we have already show
                //a newer one frame
                if (frameId < lastCompletedFrameInQueue) return;

                //If the localFrameId is corrupted or the header is from other frame then skip
                if (FramesBuffer[localFrameId].compareHeaders(frameId, frameSize, packetsNumber, localFrameId) != 0) {
                    //FramesBuffer[localFrameId].initiateFrame(); //Broken Frame
                    return;
                }

                if (!restoreCorruptedPacket) {

                    System.arraycopy(datagramData, headerLen, currentPacketData, 0, packetDataSize);
                }


                //FramesBuffer[localFrameId].initiateFrame(packetsNumber, frameId, frameSize, localFrameId);
                FramesBuffer[localFrameId].addDataToBuffer(currentPacketData, packetDataSize, packetId, corruptedPacketLength, restoreCorruptedPacket);

                //If the frame was completed then add it into a FIFO queue
                if (FramesBuffer[localFrameId].frameIsFilled()) {
                    lastCompletedFrameInQueue = localFrameId;
                    imagesQueue.add(FramesBuffer[localFrameId].clone());

                    //Clear the frames (not the the Queue) buffer which are between 2 consecutive completed frames
                    if (localFrameId >= prevLocalFrameId) {
                        for (int i = prevLocalFrameId; i <= localFrameId; i++)
                            FramesBuffer[i].initiateFrame();
                    } else {
                        for (int i = prevLocalFrameId; i < maxImagesStored; i++)
                            FramesBuffer[i].initiateFrame();

                        for (int i = 0; i < localFrameId; i++)
                            FramesBuffer[i].initiateFrame();
                    }
                    prevLocalFrameId = localFrameId;
                }
                //esp32Ip = phoneDpReceive.getAddress();
                //esp32Port = phoneDpReceive.getPort();
            }catch (IOException e) {
                e.printStackTrace();
            }

        }

        public void displayImages(){
            FrameReceivedObject imgObj = imagesQueue.poll();
            Log.d("Myti", "Queue images size: " + imagesQueue.size());
            if (imgObj != null) {
                try {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(imgObj.buffer, 0, imgObj.frameSize);
                    Log.d("Myti", "frameSize " + frameSize);
                    if (bitmap != null) {
                        mainActivity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // Stuff that updates the UI
                                imageView.setImageBitmap(bitmap);
                            }
                        });

                        timePerFrameSum += (int) 1000 / (System.currentTimeMillis() - timePrevFrame);
                        framesCount++;
                        displayStat_bo = true;
                    }

                    timePrevFrame = System.currentTimeMillis();
                } catch (Exception e) {
                    Log.e("Myti", "Exception in try to display image in view", e);
                }
            }
        }

        public boolean checkRequestForResponseTime(){
            if(packetDataSize < customMsgFromServerLen) {
                int prevTimeOut=1;
                int maxRespTime = 100;
                byte[] msg= respTimeMsg.getBytes(StandardCharsets.UTF_8);
                byte[] txBufferReceive = new byte[headerLen+msg.length];
                long timeStart = 0;
                String msgFromServer = new String(Arrays.copyOfRange(datagramData, headerLen, headerLen + packetDataSize), Charset.forName("UTF-8"));

                if (respTimeMsg.equals(msgFromServer)) {
                    DatagramPacket dp = new DatagramPacket(ACKPacketBf, ACKPacketBf.length, phoneDpReceive.getAddress(), phoneDpReceive.getPort());

                    try {
                        prevTimeOut = phoneSocket.getSoTimeout();
                        timeStart = System.currentTimeMillis();
                        phoneSocket.send(dp);
                    } catch (Exception e) {
                        Log.d("Myti", "Exception into getResponseTime function into Phone thread receive, send message");
                        e.printStackTrace();
                    }

                    try {
                        phoneSocket.setSoTimeout(maxRespTime);
                        try {
                            //Arrays.fill(txBuffer, (byte) 0);
                            DatagramPacket phoneDpReceive = new DatagramPacket(txBufferReceive, txBufferReceive.length);
                            //serverSocketSendDataTest.getSoTimeout();
                            phoneSocket.receive(phoneDpReceive);
                        } catch (Exception e) {
                            Log.d("Myti", "Exception into getResponseTime function into Phone thread receive, receive message");
                            e.printStackTrace();
                        }
                        responseTimePhone = (int) (System.currentTimeMillis() - timeStart);
                        phoneSocket.setSoTimeout(prevTimeOut);
                    } catch (Exception e) {
                        Log.d("Myti", "Exception into getResponseTime function into Phone thread receive, set timeOut");
                        e.printStackTrace();
                    }
                    return true;
                }
            }
            return false;
        }

        @SuppressLint("SetTextI18n")
        public void displayStatistics(Activity mainActivity){
            //Display performance info
            float timePassed = (System.currentTimeMillis()-startTime)/((float)1000);
            if(timePassed > 1 && displayStat_bo){ // time > 1sec
                PER = (float)(PCount - PErrors)/ PCount; //Packets error rate. Value of 1 means 100% success without errors, 0 means all packet have error.
                String FPS_st = "FPS : "+ (int)(timePerFrameSum /(framesCount *timePassed));
                String KbpsAll_st = "Kbps(ALL): " + (int) ((Kbps/1000) / timePassed);
                String PER_st = "PER: " + String.format("%.03f", PER);
                String KbpsSucceed_st = "Kbps(Suc): " + (int) ((KbpsSucceed/1000) / timePassed);    //division with 1000 means kb

                mainActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {

                        // Stuff that updates the UI
                        textViewFPS.setText(FPS_st);
                        textViewKbps.setText(KbpsAll_st);
                        textViewPER.setText(PER_st);
                        textViewKbpsSucceed.setText(KbpsSucceed_st);
                    }
                });

                startTime = System.currentTimeMillis();
                timePerFrameSum = 0;
                Kbps = 0;
                KbpsSucceed = 0;
                framesCount = 0;
                PCount = 1;
                PErrors = 0;
                PER = 0;
                Log.e("Myti", "Time Passed = "+ timePassed);
            }
        }

        public void sendMessage(String message) {
            try {
                if (null != phoneSocket) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            byte[] data = message.getBytes();
                            try {
                                DatagramPacket dp = new DatagramPacket(data, data.length, esp32Ip, esp32Port);
                                phoneSocket.send(dp);
                            } catch (IOException e) {
                                Log.d("Myti", "Exception server not ip for client");
                                e.printStackTrace();
                            }
                        }
                    }).start();
                }
            } catch (Exception e) {
                Log.d("Myti", "Exception server not ip for client");
                e.printStackTrace();
            }
        }

    }

    public class FrameReceivedObject {
        int maxObjectLength = 40000;    //Max size 40kbytes
        byte[] buffer = new byte[maxObjectLength];
        int objLength = 0;
        int packetsNumber = -1;
        int packetsNumberCounter = 0;
        int frameId = -1;
        int frameSize = -1;
        int localFrameId = -1;
        boolean frameIsBroken = false;
        boolean skipThisFrame = false;

        /*Compare the headers */
        public byte compareHeaders(int crnFrameId, int crnFrameSize, int crnPacketsNumber, int crnLocalFrameId) {

            if (frameId == -1) {
                frameId = crnFrameId;
            } else if (frameId != crnFrameId) {
                //initiateFrame();
                return 1;
            }

            if (frameSize == -1) {
                if (crnFrameSize < maxObjectLength) {
                    frameSize = crnFrameSize;
                } else {
                    //initiateFrame();
                    return 5;
                }
            } else if (frameSize != crnFrameSize) {
                return 2;
            }

            if (packetsNumber == -1) {
                if (crnPacketsNumber * packetsDataLength < maxObjectLength) {
                    packetsNumber = crnPacketsNumber;
                } else {
                    //initiateFrame();
                    return 6;
                }
            } else if (packetsNumber != crnPacketsNumber) {
                return 3;
            }

            if (localFrameId == -1) {
                if (crnLocalFrameId < maxImagesStored && crnLocalFrameId >= 0) {
                    localFrameId = crnLocalFrameId;
                } else {
                    //initiateFrame();
                    return 7;
                }
            } else if (localFrameId != crnLocalFrameId) {
                return 4;
            }

            /* Same headers */
            return 0;
        }

        public byte  addDataToBuffer(byte[] packetData, int packetLength, int packetId, int corruptedDataLength, boolean restoreCorruptedPacket) {
            if (packetId * packetsDataLength + packetLength > maxObjectLength /*|| packetLength != packetsDataLength*/)
                return 0;

            System.arraycopy(packetData, 0, buffer, packetId * packetsDataLength, restoreCorruptedPacket?packetsDataLength:packetLength);
            objLength += packetLength - corruptedDataLength;

            if(corruptedDataLength==0)
                packetsNumberCounter++;
            return 1;
        }

        public boolean frameIsFilled() {
            return objLength >= frameSize || packetsNumberCounter >= packetsNumber;
        }

        public void initiateFrame() {
            objLength = 0;
            packetsNumber = -1;
            frameId = -1;
            frameSize = -1;
            packetsNumberCounter=0;
            localFrameId = -1;
            frameIsBroken = false;
            skipThisFrame = false;

        }

        public void initiateFrame(int packetsNumber, int frameId, int frameSize, int localFrameId) {
            if (localFrameId == -1) {
                this.packetsNumber = packetsNumber;
                this.frameId = frameId;
                this.frameSize = frameSize;
                this.localFrameId = localFrameId;
            }
        }

        @NonNull
        @Override
        public FrameReceivedObject clone(){
            FrameReceivedObject objCopy = new FrameReceivedObject();
            System.arraycopy(this.buffer, 0, objCopy.buffer, 0, this.buffer.length);
            objCopy.objLength = this.objLength;
            objCopy.packetsNumber = this.packetsNumber;
            objCopy.packetsNumberCounter = this.packetsNumberCounter;
            objCopy.frameId = this.frameId;
            objCopy.frameSize = this.frameSize;
            objCopy.localFrameId = this.localFrameId;
            objCopy.frameIsBroken = this.frameIsBroken;
            objCopy.skipThisFrame = this.skipThisFrame;
            return objCopy;
        }


    }

    //-------------------------------------------SERVER-------------------------------------------

    public class TestSendDataCommunicationThread extends AppCompatActivity implements Runnable {
        private Context context;
        //This is the socket int the server side(esp32) from which we send the data
        public DatagramSocket serverSocketSendDataTest;

        //This is the socket int the server side(esp32) from which we send the data for checking the response time between client and the server
        public DatagramSocket serverSocketSReceiveResponseTimeTest;

        //The port is the specified port in the client side to which we send the data,
        //we know that port in advance, or after communicating with the client by any means
        public int serverPortSendDataTest;

        public  int maxRespTime = 100;
        public int responseTimeServer=0;
        public int serverPortReceiveResponseTimeTest;
        /*
        private byte buf[] = new byte[packetLen + headerLen];
        private DatagramPacket phoneDpReceive = new DatagramPacket(buf, buf.length);
        File root = Environment.getExternalStorageDirectory();
        */

        static final byte imageType = 0;
        static final byte audioType = 1;
        ImageView imageView;
        InetAddress phoneIpReceiveDataTest;
        int phonePortReceiveDataTest;
        // This can be set to a specific value after measuring the response time
        // between the server and the client.
        int notificationMsgWaitTime = 1; // 1ms
        long checkResponseTime;

        public TestSendDataCommunicationThread(ImageView imageView, InetAddress localIP, Context current) {
            try {
                this.context = current;
                serverPortSendDataTest = 8000;
                serverSocketSendDataTest = new DatagramSocket(serverPortSendDataTest);
                serverSocketSendDataTest.setSoTimeout(notificationMsgWaitTime);  // Set socket timeout, how much to wait for notification messages
                phoneIpReceiveDataTest = localIP;
                phonePortReceiveDataTest = 8081;
                serverPortReceiveResponseTimeTest = 8082;
                serverSocketSReceiveResponseTimeTest = new DatagramSocket(serverPortReceiveResponseTimeTest);

            } catch (IOException e) {
                Log.e("Myti", "Exception in photoCallback", e);
            }
            this.imageView = imageView;
        }

        public void run() {
            try {
                if (null != serverSocketSendDataTest) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            byte[][] dataImages = new byte[3][];
                            int frameCount = 0;
                            byte localFrameId = 0;
                            long prevTime = 0;

                            //responseTimeServer = getResponseTime();
                            //notificationMsgWaitTime = responseTimeServer +1;
                            try {
                                // Set socket timeout, how much to wait for notification messages
                                serverSocketSendDataTest.setSoTimeout(notificationMsgWaitTime);
                            }catch (IOException e) {
                                Log.e("Myti", "Exception in photoCallback", e);
                            }

                            checkResponseTime = System.currentTimeMillis();
                            while(true){
                                //Check each 5 sec the response time between server and client
                                if(System.currentTimeMillis() - checkResponseTime > 5000){
                                    responseTimeServer = getResponseTime();
                                    //notificationMsgWaitTime = responseTimeServer +1;
                                    try {
                                        // Set socket timeout, how much to wait for notification messages
                                        serverSocketSendDataTest.setSoTimeout(notificationMsgWaitTime);
                                    }catch (IOException e) {
                                        Log.e("Myti", "Exception in photoCallback", e);
                                    }
                                    checkResponseTime = System.currentTimeMillis();
                                }

                                try {
                                    //Drawable drawable = getResources().getDrawable(getResources().getIdentifier("img1.jpeg", "drawable", getPackageName()));
                                    for (int i = 0; i < 3; i++) {
                                        for(int j=0;j<1;j++){
                                            String fnm = "p" + String.valueOf(i + 1); //  this is image file name
                                            String PACKAGE_NAME = context.getPackageName();
                                            int imgId = context.getResources().getIdentifier(PACKAGE_NAME + ":drawable/" + fnm, null, null);
                                            Bitmap bMap = BitmapFactory.decodeResource(context.getResources(), imgId);
                                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                            bMap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
                                            dataImages[i] = baos.toByteArray();
                                            long timeInterval = 10; //31.25 fps for 31 milliseconds intervals
                                            long timeWait = timeInterval - (System.currentTimeMillis()-prevTime);
                                            if (timeWait > 0)
                                                Thread.sleep(timeWait);

                                            prevTime = System.currentTimeMillis();
                                            sendDataUdp(dataImages[i], dataImages[i].length, frameCount, localFrameId);

                                            frameCount++;
                                            localFrameId++;
                                            if (localFrameId == maxImagesStored) localFrameId = 0;
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.d("Myti", "Exception server not ip for client");
                                    e.printStackTrace();
                                }

                            }
                        }
                    }).start();
                }
            } catch (Exception e) {
                Log.d("Myti", "Exception server not ip for client");
                e.printStackTrace();
            }
        }

        public int getResponseTime(){
            int prevTimeOut=1;
            long tempResponseTime = maxRespTime;
            int retriesNum = 5;
            byte[] msg= respTimeMsg.getBytes(StandardCharsets.UTF_8);
            byte[] txBuffer = new byte[headerLen+msg.length];
            byte[] txBufferReceive = new byte[headerLen+msg.length];
            constructDataIntoBuffer(msg, txBuffer, 0, msg.length, (new short[]{(short)msg.length}),
                    (short)1, (byte)0, (short)0, defaultResendPacketCode);
            DatagramPacket dp ;
            DatagramPacket dp1;
            long timeStart= 0;

            while(retriesNum>0 && tempResponseTime >= maxRespTime) {
                retriesNum--;

                try {
                    prevTimeOut = serverSocketSReceiveResponseTimeTest.getSoTimeout();
                    dp = new DatagramPacket(txBuffer, headerLen + msg.length, phoneIpReceiveDataTest, phonePortReceiveDataTest);
                    timeStart= System.currentTimeMillis();
                    serverSocketSReceiveResponseTimeTest.send(dp);
                } catch (Exception e) {
                    Log.d("Myti", "Exception into getResponseTime function, send message");
                    e.printStackTrace();
                    continue;   //Zero means Error
                }

                try {
                    serverSocketSReceiveResponseTimeTest.setSoTimeout(maxRespTime);
                    try {
                        //Arrays.fill(txBuffer, (byte) 0);
                        DatagramPacket phoneDpReceive = new DatagramPacket(txBufferReceive, txBufferReceive.length);
                        //serverSocketSendDataTest.getSoTimeout();
                        serverSocketSReceiveResponseTimeTest.receive(phoneDpReceive);
                    } catch (Exception e) {
                        Log.d("Myti", "Exception into getResponseTime function, receive message");
                        e.printStackTrace();
                    }
                    tempResponseTime = System.currentTimeMillis()-timeStart;
                    serverSocketSReceiveResponseTimeTest.setSoTimeout(prevTimeOut);
                    //Resent a package to client to calculate also the response time
                    dp1 = new DatagramPacket(txBuffer, headerLen + msg.length, phoneIpReceiveDataTest, phonePortReceiveDataTest);
                    serverSocketSReceiveResponseTimeTest.send(dp1);
                } catch (Exception e) {
                    Log.d("Myti", "Exception into getResponseTime function, set timeOut");
                    e.printStackTrace();
                }

            }

            return (int) (tempResponseTime >= maxRespTime ? 0:tempResponseTime);
        }

        public void sendDataUdp(byte[] frame, int frameSize, int frameId, byte localFrameId) {
            short packetsNumber = 0;  //The number of packets after splitting all the frame
            short packetId = 0;       //The ID number(counter) of the current packet
            short lastPacketLen = 0;     //The last packet has usually smaller length than other because is truncated
            short[] currentPacketDataLen = new short[1];
            byte[] txBuffer = new byte[datagramPacketLength];
            //frameId //The ID number(counter) of the current frame(all picture)
            byte[] buffConfirm = new byte[confirmationMessageLen];

            currentPacketDataLen[0] = (short) (packetsDataLength);
            packetsNumber = (short)((frameSize/packetsDataLength) + (frameSize%packetsDataLength==0?0:1));
            lastPacketLen = (short)(frameSize - packetsDataLength*((int)(packetsNumber)-1));

            if(frameSize<=packetsDataLength){
                currentPacketDataLen[0] = (short) frameSize;
                lastPacketLen =(short) frameSize;
                packetsNumber = 1;
            }

            //Send all the frame into packets for the first time
            while(packetId<packetsNumber){
                if(packetId == packetsNumber-1) currentPacketDataLen[0] = lastPacketLen;

                //Construct the data inside the buffer
                constructDataIntoBuffer(frame, txBuffer, frameId, frameSize,
                        currentPacketDataLen, packetsNumber, localFrameId, packetId,defaultResendPacketCode);

                //if(packetId < 10)
                  //  SimulateError(txBuffer,100,1);

                //DatagramPacket dp = new DatagramPacket(txBuffer, headerLen+currentPacketLen, phoneIpReceiveDataTest, phonePortReceiveDataTest);
                DatagramPacket dp = new DatagramPacket(txBuffer, headerLen+currentPacketDataLen[0], phoneIpReceiveDataTest, phonePortReceiveDataTest);
                try {
                    serverSocketSendDataTest.send(dp);
                }catch (Exception e){
                    Log.d("Myti", "Exception server not ip for client");
                    e.printStackTrace();
                }
                packetId++;
            }

            // Check the notifications messages and resend the broken packets
            while (true){
                try {
                    DatagramPacket phoneDpReceive = new DatagramPacket(buffConfirm, buffConfirm.length);
                    //serverSocketSendDataTest.getSoTimeout();
                    serverSocketSendDataTest.receive(phoneDpReceive);
                    //phoneDpReceive.getData();

                    short tempPacketId = buffConfirm[0];
                    int tempFrameId = ((buffConfirm[4] & 0x00ff) << 24) | ((buffConfirm[3] & 0x00ff) << 16) |
                            ((buffConfirm[2] & 0x00ff) << 8) | (buffConfirm[1] & 0xff);
                    byte tempLocalFrameId = buffConfirm[5];
                    byte tempErrorPart = buffConfirm[6];

                    if (tempFrameId == frameId && tempLocalFrameId == localFrameId && tempErrorPart!=defaultResendPacketCode){
                        if(tempPacketId == packetsNumber-1){
                            currentPacketDataLen[0] = lastPacketLen;
                        }else{
                            currentPacketDataLen[0] = (short) (packetsDataLength);
                        }

                        //Construct the data inside the buffer
                        constructDataIntoBuffer(frame, txBuffer, frameId, frameSize,
                                currentPacketDataLen, packetsNumber, localFrameId, tempPacketId,tempErrorPart);

                        DatagramPacket dp = new DatagramPacket(txBuffer, headerLen+currentPacketDataLen[0], phoneIpReceiveDataTest, phonePortReceiveDataTest);
                        try {
                            serverSocketSendDataTest.send(dp);
                        }catch (Exception e){
                            Log.d("Myti", "Exception server not ip for client");
                            e.printStackTrace();
                        }
                    }
                }catch (Exception e){
                    Log.d("Myti", "There is not notification message for erroneous packet");
                    e.printStackTrace();
                    break;
                }

            }

        }

        public void constructDataIntoBuffer(byte[] frame, byte[] txBuffer,
                                            int frameId, int frameSize,
                                            short[] currentPacketDataLen, short packetsNumber,
                                            byte localFrameId, short packetId, byte resendPacket){

            //short checkSumsLen = 256;
            byte checkSum[];
            int packetOffset = packetId*packetsDataLength;

            // Initialize array
            //Arrays.fill(txBuffer, (byte) 0);

            //Check the resend packet code and adjust the data needed to be resend accordingly
            if(resendPacket!=defaultResendPacketCode ){
                boolean[] pos = {false, false, false, false};
                int startPosFrame = 0;
                int startPosBuff = 0;
                int lengthData = 0;
                int prevStep = 0;
                int overallLength = 0;

                for(int i=0;i<pos.length;i++) {
                    pos[i] = (resendPacket & (0b000000001 << i)) != 0;
                }

                for(int i=0;i<pos.length;i++) {
                    if (pos[i]) {
                        prevStep = checkSumPositions[i] - checkSumPositions[0];
                        startPosFrame = packetOffset + prevStep;
                        startPosBuff = checkSumPositions[0] + overallLength;
                        lengthData = checkSumPositions[i + 1] - checkSumPositions[i];
                        lengthData = overallLength +lengthData > currentPacketDataLen[0] ? (currentPacketDataLen[0] - overallLength) : lengthData;
                        System.arraycopy(frame, startPosFrame, txBuffer, startPosBuff, lengthData);
                        overallLength += lengthData;
                    }
                }

                currentPacketDataLen[0] = (short)overallLength;
            }
            else{
                //Copy the piece of frame into txBuffer
                System.arraycopy( frame, packetOffset, txBuffer, headerLen, currentPacketDataLen[0]);
            }

            //Header construction
            txBuffer[1] = (byte)(frameId & 0xff);
            txBuffer[2] = (byte)(frameId>>8 & 0xff);
            txBuffer[3] = (byte)(frameId>>16 & 0xff);
            txBuffer[4] = (byte)(frameId>>24 & 0xff);
            txBuffer[5] = (byte)(frameSize & 0xff);
            txBuffer[6] = (byte)(frameSize>>8 & 0xff);
            txBuffer[7] = (byte)(frameSize>>16 & 0xff);
            txBuffer[8] = (byte)(frameSize>>24 & 0xff);
            txBuffer[9] = (byte)packetsNumber;
            txBuffer[10] = (byte)packetId;
            txBuffer[11] = (byte)(packetsDataLength & 0xff);
            txBuffer[12] = (byte)(packetsDataLength>>8 & 0xff);
            txBuffer[13] = (byte)(currentPacketDataLen[0] & 0xff);
            txBuffer[14] = (byte)(currentPacketDataLen[0]>>8 & 0xff);
            txBuffer[15] = localFrameId;
            txBuffer[16] = imageType;    //Image type
            //Faulty packets positions (is used when we resend the data)
            txBuffer[17] = resendPacket;
            //CheckSum set to zero
            txBuffer[18] = 0;
            txBuffer[19] = 0;
            txBuffer[20] = 0;
            txBuffer[21] = 0;
            txBuffer[22] = 0;
            txBuffer[23] = 0;
            txBuffer[24] = 0;
            txBuffer[25] = 0;
            //Reserved for future data
            txBuffer[26] = 0;
            txBuffer[27] = 0;
            txBuffer[28] = 0;
            txBuffer[29] = 0;


            //Calculate checkSums
            //checkSum = CalcCheckSums(txBuffer, NumOfChkSums, headerLen+currentPacketDataLen[0]);
            checkSum = Fletcher16(txBuffer, checkSumPositions, headerLen+currentPacketDataLen[0]);
            //checkSum = XOR_CheckSum(txBuffer, checkSumPositions, headerLen+currentPacketDataLen[0]);

            //Store the checkSum into header
            for(int j=0;j<NumOfChkSums;j++) {
                txBuffer[headerLen-NumOfChkSums- reservedBytesNum + j] = checkSum[j];
            }

        }

    } // End of class

    public void SimulateError(byte[] dataBuffer, int percentage, int errorsNumber){
        int max = dataBuffer.length-1;
        int min = headerLen+1;
        if ((new Random()).nextInt(100) >= (100 - percentage)) {
            for (int i=0;i<errorsNumber;i++) {
                dataBuffer[(new Random()).nextInt( (max - min) + 1) + min] = (byte) (new Random()).nextInt(255);
            }
        }

       /* int i=0;
        dataBuffer[checkSumPositions[i]+1] = (byte) (new Random()).nextInt(255);
        dataBuffer[checkSumPositions[i]+2] = (byte) (new Random()).nextInt(255);

        i++;
        dataBuffer[checkSumPositions[i]+1] = (byte) (new Random()).nextInt(255);
        dataBuffer[checkSumPositions[i]+2] = (byte) (new Random()).nextInt(255);

        i++;
        dataBuffer[checkSumPositions[i]+1] = (byte) (new Random()).nextInt(255);
        dataBuffer[checkSumPositions[i]+2] = (byte) (new Random()).nextInt(255);

        i++;
        dataBuffer[checkSumPositions[i]+1] = (byte) (new Random()).nextInt(255);
        dataBuffer[checkSumPositions[i]+2] = (byte) (new Random()).nextInt(255);*/
    }

    // Split the datagramData into chkSumsNum equal of length pieces and calculate for each the checkSum
    public byte[] CalcCheckSums(byte[] datagramData, int chkSumsNum, int currentPacketLength)
    {
        int checkSumStep = (int) Math.floor(datagramPacketLength/chkSumsNum);
        int checkSumAdd[] = new int[chkSumsNum];
        byte checkSumFinal[] = new byte[chkSumsNum];

        int checkSum = 0;
        for(int j=0;j<chkSumsNum;j++) {
            checkSum = 0;
            int endPos = (j+1)*checkSumStep;
            //if (j==chkSumsNum-1) endPos = datagramPacketLength;
            if (endPos > currentPacketLength)  endPos = currentPacketLength;

            for (int byteI = j*checkSumStep; byteI < endPos; byteI++) {
                checkSum += Byte.toUnsignedInt(datagramData[byteI]);
            }

            // Add all checksum bytes
            checkSumAdd[j] = (checkSum & 0xff) + (checkSum >> 8 & 0xff) + (checkSum >> 16 & 0xff) + (checkSum >> 24 & 0xff);
            while ((checkSumAdd[j] >> 8 & 0xff) != 0b00000000)
            {
                checkSumAdd[j] = (checkSumAdd[j] & 0xff) + (checkSumAdd[j] >> 8 & 0xff);
            }

            checkSumFinal[j] = (byte) (checkSumAdd[j] & 0xff);
        }

        return checkSumFinal;
    }

    public byte[] CalcCheckSumsPositions(byte[] datagramData,int[] positions, int currentPacketLength)
    {
        int chkSumsNum = positions.length-1;
        int checkSumAdd[] = new int[2*chkSumsNum];
        byte checkSumFinal[] = new byte[2*chkSumsNum];


        int checkSum = 0;
        for(int j=0;j<positions.length-1;j++) {
            checkSum = 0;
            int startPos = positions[j];
            int endPos = positions[j+1];

            if (endPos > currentPacketLength)  endPos = currentPacketLength;

            for (int byteI = startPos; byteI < endPos; byteI++) {
                checkSum += (byteI|Byte.toUnsignedInt(datagramData[byteI]) + byteI)|Byte.toUnsignedInt(datagramData[byteI]);
            }

            /*
            // Add all checksum bytes
            checkSumAdd[j] = (checkSum & 0xff) + (checkSum >> 8 & 0xff) + (checkSum >> 16 & 0xff) + (checkSum >> 24 & 0xff);
            while ((checkSumAdd[j] >> 8 & 0xff) != 0b00000000)
            {
                checkSumAdd[j] = (checkSumAdd[j] & 0xff) + (checkSumAdd[j] >> 8 & 0xff);
            }
            */

            checkSumFinal[2*j]   = (byte) (checkSum>>8 & 0xff);
            checkSumFinal[2*j+1] = (byte) (checkSum & 0xff);

        }

        return checkSumFinal;
    }

    public byte[] Fletcher16(byte[] datagramData,int[] positions, int currentPacketLength)
    {
        int chkSumsNum = positions.length-1;
        byte checkSumFinal[] = new byte[2*chkSumsNum];
        int sum1 = 0;
        int sum2 = 0;

        for(int j=0;j<positions.length-1;j++) {
            int startPos = positions[j];
            int endPos = positions[j+1];

            if (endPos > currentPacketLength)  endPos = currentPacketLength;

            for (int byteI = startPos; byteI < endPos; byteI++) {
                sum1 = (sum1 + datagramData[byteI]) ;
                sum2 = (sum2 + sum1) ;
            }

            checkSumFinal[2*j]   = (byte) (sum1 & 0xff) ;
            checkSumFinal[2*j+1] = (byte) (sum2 & 0xff);
        }

        return checkSumFinal;
    }

    public byte[] XOR_CheckSum(byte[] datagramData,int[] positions, int currentPacketLength)
    {
        int chkSumsNum = positions.length-1;
        byte checkSumFinal[] = new byte[2*chkSumsNum];
        int xor = 0;

        for(int j=0;j<positions.length-1;j++) {
            int startPos = positions[j];
            int endPos = positions[j+1];

            if (endPos > currentPacketLength)  endPos = currentPacketLength;

            for (int byteI = startPos; byteI < endPos; byteI++) {
                xor = (xor + datagramData[byteI]) & 0xFF;
                xor = (((xor ^ 0xFF) + 1) & 0xFF);
            }

            checkSumFinal[2*j]   = (byte) (xor & 0xff) ;
            checkSumFinal[2*j+1] = (byte) (xor & 0xff);
        }

        return checkSumFinal;
    }
}
