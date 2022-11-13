package com.example.serverUDPImage;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;

public class Streaming {
    int packetsDataLength = 920;
    int datagramPacketLength = 1024;
    byte maxImagesStored = 100;
    byte headerLen = 21;
    int NumOfChkSums = 4;

    public class CommunicationReceiveThread implements Runnable {
        public DatagramSocket phoneSocket;
        public int phonePort;
        byte[] startFrameDelimiter = {10, 10, 5, 10, 10};
        byte[] startPacketDelimiter = {5, 50, 5, 50, 50};
        private byte buf[] = new byte[datagramPacketLength];
        private DatagramPacket phoneDpReceive = new DatagramPacket(buf, buf.length);

        Queue<FrameReceivedObject> imagesQueue = new LinkedList<>();
        int lastCompletedFrameInQueue = -1;
        FrameReceivedObject[] FramesBuffer = new FrameReceivedObject[maxImagesStored];

        byte[] currentPacketData = new byte[10000];
        byte[] datagramData = new byte[1];;
        boolean searchFrame = false;
        int frStart;
        int delimiterLength;
        byte[] header;
        int currentPacketLength = 0;
        byte packetType;
        static final byte imageType = 0;
        static final byte audioType = 1;
        int packetSize;
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
        InetAddress esp32Ip;
        int esp32Port;
        //Time measure for FPS calculation
        int FPS = 0;
        float PER =0;//Packets Error Rate, how many packet received have error divided by the total packets received
        int PErrors = 0; //Number of packets with errors
        int PCount = 0; //Packets Received
        int Kbps = 0;
        int fpsCount = 0;
        long startTime = System.currentTimeMillis();
        long timePrevFrame = System.currentTimeMillis();
        int ackBuffLen = 2+1+4+1;  // OKPacket(2), packetId(1), frameId(4), localFrameId(1)
        byte ACKPacketBf[] = new byte[ackBuffLen];
        boolean skipIteration = false;


        public CommunicationReceiveThread(Activity mainActivity, ArrayList<View> listViewObjects) {
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

        }

        public int findDelimiter(byte[] data, byte[] delimiter) {
            int i = headerLen;
            int j = 0;
            while (i < data.length) {
                for (j = 0; j < delimiter.length; j++) {
                    if (i + j >= data.length) return -1;
                    if (data[i + j] != delimiter[j]) break;
                }
                if (j == delimiter.length) return i;
                i++;
            }
            return -1;
        }

        @SuppressLint("SetTextI18n")
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                if (this.imageView != null) {
                    //continue;
                }

                Log.d("Myti", "Get data");
                try {
                    // Initialize array
                    Arrays.fill(datagramData, (byte) 0);
                    phoneSocket.receive(phoneDpReceive);
                    datagramData = phoneDpReceive.getData();
                    skipIteration = false;
                    //Find frame start
                    if (searchFrame) {
                        frStart = findDelimiter(datagramData, startFrameDelimiter);
                        if (frStart == -1) continue;
                        searchFrame = false;
                        delimiterLength = startFrameDelimiter.length;
                    } else {
                        frStart = findDelimiter(datagramData, startPacketDelimiter);
                        searchFrame = false;
                        if (frStart == -1) continue;
                        delimiterLength = startPacketDelimiter.length;
                    }

                    /* currentPacketLength = phoneDpReceive.getLength() - frStart - delimiterLength;
                    System.arraycopy(datagramData, frStart + delimiterLength, currentPacketData, 0, currentPacketLength);*/
                    header = Arrays.copyOfRange(datagramData, frStart - headerLen, headerLen);
                    //For image we send 0b00000000 and for audio 0b11111111, because of the udp bits corruption
                    //we count the number of 1 in the byte. 0, 1, 2, 3 number of 1 means image type
                    //and 4, 5, 6, 7 ,8 number of 1 means audio type.
                    frameId = ((header[4] & 0x00ff) << 24) | ((header[3] & 0x00ff) << 16) |
                            ((header[2] & 0x00ff) << 8) | (header[1] & 0xff);
                    frameSize = ((header[8] & 0x00ff) << 24) | ((header[7] & 0x00ff) << 16) |
                            ((header[6] & 0x00ff) << 8) | (header[5] & 0xff);
                    packetsNumber = header[9] & 0xff;
                    packetId = (header[10] & 0xff);
                    packetSize = ((header[14] & 0xff) << 8) | (header[13] & 0xff);  //Δεν χρειάζεται, θα είναι fix 1024bytes για όλα τα πακέτα
                    localFrameId = (header[15] & 0xff);
                    packetType = Integer.bitCount(header[16]) > 3 ? audioType : imageType;

                    currentFrameIsBroken = false;

                    //Check the CheckSum to insure the integrity of the data
                    for (int i=0;i<NumOfChkSums;i++) {
                        //Get received checkSums
                        receivedCheckSum[i] = datagramData[headerLen - NumOfChkSums + i];

                        //Set to zero the checkSum fields and calculate the checksum of the current packet
                        datagramData[headerLen - NumOfChkSums + i] = 0;
                    }

                    checkSums = CalcCheckSums(datagramData, NumOfChkSums, frStart + delimiterLength +packetSize);

                    //Calculate Packet Error Rate And errors in packet, we divide the received packet into 'NumOfChkSums' number of chunks
                    PCount += NumOfChkSums;
                    for (int i=0;i<NumOfChkSums;i++) {
                        if (checkSums[i] != receivedCheckSum[i]){
                            PErrors++;
                            currentFrameIsBroken= true;
                        }
                    }

                    if (checkSums[0] != receivedCheckSum[0]) skipIteration=true;

                    if (currentFrameIsBroken) skipIteration=true;

                    if(skipIteration) {
                        //Send message back to the server (esp32 board)
                        if (skipIteration) {
                            ACKPacketBf[0] = 0;
                            ACKPacketBf[1] = 0;
                        } else {
                            ACKPacketBf[0] = 1;
                            ACKPacketBf[1] = 1;
                        }
                        ACKPacketBf[2] = header[10];    //packetId
                        ACKPacketBf[3] = header[1];     //frameId1
                        ACKPacketBf[4] = header[2];     //frameId2
                        ACKPacketBf[5] = header[3];     //frameId3
                        ACKPacketBf[6] = header[4];     //frameId4
                        ACKPacketBf[7] = header[15];    //localFrameId

                        DatagramPacket dp = new DatagramPacket(ACKPacketBf, ACKPacketBf.length, esp32Ip, esp32Port);
                        phoneSocket.send(dp);
                    }
                    if(skipIteration) continue;

                    if (localFrameId >= maxImagesStored || localFrameId < 0) {
                        //The other values from the header can be used so as the frame be restored, for example we
                        //can compare the values frameId or packetId or frameSize with the values of the store frameObject and find
                        //the right location of the packet, for the moment we just skip this step and we implement this in the future
                        continue;
                    }

                    //If the current received packet is from an old frame and we have in the queue
                    //a newer completed frame then we can skip this packet because we have already show
                    //a newer one frame
                    if (frameId < lastCompletedFrameInQueue) continue;

                    //If the localFrameId is corrupted or the header is from other frame then skip
                    if (FramesBuffer[localFrameId].compareHeaders(frameId, frameSize, packetsNumber, localFrameId) != 0) {
                        //FramesBuffer[localFrameId].initiateFrame(); //Broken Frame
                        continue;
                    }

                    System.arraycopy(datagramData, frStart + delimiterLength, currentPacketData, 0, packetSize);
                    //FramesBuffer[localFrameId].initiateFrame(packetsNumber, frameId, frameSize, localFrameId);
                    FramesBuffer[localFrameId].addDataToBuffer(currentPacketData, packetSize, packetId);

                    if (FramesBuffer[localFrameId].frameIsFilled()) {
                        lastCompletedFrameInQueue = frameId;
                        imagesQueue.add(FramesBuffer[localFrameId].clone());
                        //Clear the frames buffer which are between 2 consecutive completed frames
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


                        //System.arraycopy( currentPacketData, 0, imagesObj[localFrameId].buffer, destPos, currentPacketLength);
                        //Save photo
                        FrameReceivedObject imgObj = imagesQueue.poll();
                        // Add an error inside the buffer

                        if (imgObj != null) {

                            File photo = new File(Environment.getExternalStorageDirectory(),
                                    "photo.jpeg");

                            if (photo.exists()) {
                                photo.delete();
                            }

                            try {
                                FileOutputStream fos = new FileOutputStream(photo.getPath());

                                /*
                                fos.write(Arrays.copyOfRange(imgObj.buffer, 0, imgObj.frameSize));
                                String s = String.valueOf(fos.getChannel().size());
                                fos.close();
                                */
                                //SimulateError(imgObj.buffer,90,1);

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


                                    FPS+= (int) 1000/(System.currentTimeMillis()-timePrevFrame);
                                    Kbps += imgObj.frameSize/1000;
                                    fpsCount++;
                                    PER = (float)(PCount - PErrors)/ PCount; //Packets error rate. Value of 1 means 100% success without errors, 0 means all packet have error.

                                }

                                float timePassed = (System.currentTimeMillis()-startTime)/((float)1000);
                                if(timePassed > 1){ // time > 1sec
                                    textViewFPS.setText("FPS : "+ (int)(FPS/(fpsCount*timePassed)));
                                    textViewKbps.setText("Kbps: " + (int) (Kbps / timePassed));
                                    textViewPER.setText("PER: " + PER);
                                    startTime = System.currentTimeMillis();
                                    FPS = 0;
                                    Kbps = 0;
                                    fpsCount = 0;
                                    PCount = 0;
                                    PErrors = 0;
                                    PER = 0;
                                    Log.e("Myti", "Time Passed = "+ timePassed);
                                }

                                timePrevFrame = System.currentTimeMillis();
                            } catch (IOException e) {
                                Log.e("Myti", "Exception in photoCallback", e);
                            }
                        }
                    }

                    //esp32Ip = phoneDpReceive.getAddress();
                    //esp32Port = phoneDpReceive.getPort();

                } catch (IOException e) {
                    Log.d("Myti", "Exception server");
                    e.printStackTrace();
                }

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

        public byte  addDataToBuffer(byte[] packetData, int packetLength, int packetId) {
            if (packetId * packetsDataLength + packetLength > maxObjectLength /*|| packetLength != packetsDataLength*/)
                return 0;
            System.arraycopy(packetData, 0, buffer, packetId * packetsDataLength, packetLength);
            objLength += packetLength;
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

    public class TestSendDataCommunicationThread extends AppCompatActivity implements Runnable {
        private Context context;
        public DatagramSocket phoneSocketSendDataTest;
        public int phonePortSendDataTest;
        /*
        private byte buf[] = new byte[packetLen + headerLen];
        private DatagramPacket phoneDpReceive = new DatagramPacket(buf, buf.length);
        File root = Environment.getExternalStorageDirectory();
        */

        int maxImageSize = 200000;
        byte[][] imageAll = new byte[2][maxImageSize];
        byte packetType;
        static final byte imageType = 0;
        static final byte audioType = 1;
        ImageView imageView;
        InetAddress phoneIpReceiveDataTest;
        int phonePortReceiveDataTest;


        public TestSendDataCommunicationThread(ImageView imageView, InetAddress localIP, Context current) {
            try {
                this.context = current;
                phonePortSendDataTest = 8000;
                phoneSocketSendDataTest = new DatagramSocket(phonePortSendDataTest);
                phoneIpReceiveDataTest = localIP;
                phonePortReceiveDataTest = 8081;

            } catch (IOException e) {
                Log.e("Myti", "Exception in photoCallback", e);
            }
            this.imageView = imageView;

        }

        public void run() {
            try {
                if (null != phoneSocketSendDataTest) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            byte[][] dataImages = new byte[3][];
                            int frameCount = 0;
                            byte localFrameId = 0;

                            while(true){

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

                                            Thread.sleep(29); //31.25 fps for 32 milliseconds intervals
                                            //Thread.sleep(10); // 60fps

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


        public void sendDataUdp(byte[] frame, int frameSize, int frameCount, byte localFrameId) {

            byte delimiterLen = 5;
            short packetsNumber = 0;  //The number of packets after splitting all the frame
            short packetId = 0;       //The ID number(counter) of the current packet
            short lastPacketLen = 0;     //The last packet has usually smaller length than other because is truncated
            short currentPacketDataLen;
            int packetOffset = 0;
            byte[] txBuffer = new byte[datagramPacketLength];
            int frameId = frameCount;  //The ID number(counter) of the current frame(all picture)

            currentPacketDataLen = (short) (packetsDataLength);
            packetsNumber = (short)((frameSize/packetsDataLength) + (frameSize%packetsDataLength==0?0:1));
            lastPacketLen = (short)(frameSize - packetsDataLength*((int)(packetsNumber)-1));

            while(packetId<packetsNumber){
                if(packetId == packetsNumber-1) currentPacketDataLen = lastPacketLen;

                packetOffset = packetId*packetsDataLength;

                //Construct the data inside the buffer
                createDataIntoBuffer(frame, txBuffer, frameId, packetOffset, frameSize, delimiterLen,
                                        currentPacketDataLen, packetsNumber, localFrameId, packetId);

                //SimulateError(txBuffer,0,1);
                //DatagramPacket dp = new DatagramPacket(txBuffer, headerLen+currentPacketLen+delimiterLen, phoneIpReceiveDataTest, phonePortReceiveDataTest);
                DatagramPacket dp = new DatagramPacket(txBuffer, headerLen+currentPacketDataLen+delimiterLen, phoneIpReceiveDataTest, phonePortReceiveDataTest);
                try {
                    phoneSocketSendDataTest.send(dp);
                }catch (Exception e){
                    Log.d("Myti", "Exception server not ip for client");
                    e.printStackTrace();
                }

                packetId++;
            }

        }

        public void createDataIntoBuffer(byte[] frame, byte[] txBuffer,
                                         int frameId, int packetOffset,
                                         int frameSize, int delimiterLen,
                                         short currentPacketDataLen,short packetsNumber,
                                         byte localFrameId, short packetId){

            //short checkSumsLen = 256;
            byte checkSum[] = {0, 0, 0, 0};

            // Initialize array
            Arrays.fill(txBuffer, (byte) 0);
            //Copy the piece of frame into txBuffer
            System.arraycopy( frame, packetOffset, txBuffer, headerLen+delimiterLen, currentPacketDataLen);

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
            txBuffer[13] = (byte)(currentPacketDataLen & 0xff);
            txBuffer[14] = (byte)(currentPacketDataLen>>8 & 0xff);
            txBuffer[15] = localFrameId;
            txBuffer[16] = imageType;    //Image type
            //CheckSum set to zero
            txBuffer[17] = 0;
            txBuffer[18] = 0;
            txBuffer[19] = 0;
            txBuffer[20] = 0;
            // Delimiters for new frame and packets
            if(packetId == -1){
                txBuffer[21] = 10;
                txBuffer[22] = 10;
                txBuffer[23] = 5;
                txBuffer[24] = 10;
                txBuffer[25] = 10;
            }
            else {
                txBuffer[21] = 5;
                txBuffer[22] = 50;
                txBuffer[23] = 5;
                txBuffer[24] = 50;
                txBuffer[25] = 50;
            }

            //Calculate checkSums
            checkSum = CalcCheckSums(txBuffer, NumOfChkSums, headerLen+currentPacketDataLen+delimiterLen);

            //Store the checkSum into header
            for(int j=0;j<NumOfChkSums;j++) {
                txBuffer[headerLen-NumOfChkSums + j] = checkSum[j];
            }

        }

    } // End of class

    public void SimulateError(byte[] dataBuffer, int percentage, int errorsNumber){
        for (int i=0;i<errorsNumber;i++) {
            if ((new Random()).nextInt(100) > (100 - percentage)) {
                dataBuffer[(new Random()).nextInt(dataBuffer.length)] = (byte) (new Random()).nextInt(255);
            }
        }
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
}
