package com.example.serverUDPImage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.util.Log;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;

public class Streaming {
    int packetsLength = 1000;
    byte maxImagesStored = 10;

    public class CommunicationReceiveThread implements Runnable {
        public DatagramSocket phoneSocket;
        public int phonePort;
        byte headerLen = 16;
        int packetLen = 1000;
        int datagramPacketLength = 1024;
        byte[] startFrameDelimiter = {10, (byte) 255, 0, (byte) 255, 10, 10, (byte) 200, 100, 1, 0};
        byte[] startPacketDelimiter = {1, 30, 1, 24, 93, (byte) 255};
        private byte buf[] = new byte[datagramPacketLength];
        private DatagramPacket phoneDpReceive = new DatagramPacket(buf, buf.length);

        Queue<FrameReceivedObject> imagesQueue = new LinkedList<>();
        int lastFrameInQueue = 0;
        FrameReceivedObject[] imagesObj = new FrameReceivedObject[maxImagesStored];
        byte[] currentPacketData = new byte[10000];
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
        int[] prevPacketSize = {0, 0};
        ImageView imageView;
        InetAddress esp32Ip;
        int esp32Port;


        public CommunicationReceiveThread(ImageView imageView) {
            try {
                phonePort = 8081;
                phoneSocket = new DatagramSocket(phonePort);
                esp32Port = 8000;
                esp32Ip = InetAddress.getByName("192.168.2.180");
            } catch (IOException e) {
                Log.e("Myti", "Exception in photoCallback", e);
            }
            this.imageView = imageView;

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

        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                if (this.imageView != null) {
                    continue;
                }

                Log.d("Myti", "Get data");
                try {
                    phoneSocket.receive(phoneDpReceive);
                    //Find frame start
                    if (searchFrame) {
                        frStart = findDelimiter(phoneDpReceive.getData(), startFrameDelimiter);
                        if (frStart == -1) continue;
                        searchFrame = false;
                        delimiterLength = startFrameDelimiter.length;
                    } else {
                        frStart = findDelimiter(phoneDpReceive.getData(), startPacketDelimiter);
                        searchFrame = true;
                        if (frStart == -1) continue;
                        delimiterLength = startPacketDelimiter.length;
                    }

                    currentPacketLength = phoneDpReceive.getLength() - frStart - delimiterLength;
                    System.arraycopy(phoneDpReceive.getData(), frStart + delimiterLength, currentPacketData, 0, currentPacketLength);
                    header = Arrays.copyOfRange(phoneDpReceive.getData(), frStart - headerLen, headerLen);
                    //For image we send 0b00000000 and for audio 0b11111111, because of the udp bits corruption
                    //we count the number of 1 in the byte. 0, 1, 2, 3 number of 1 means image type
                    //and 4, 5, 6, 7 ,8 number of 1 means audio type.
                    packetType = Integer.bitCount(header[0]) > 3 ? audioType : imageType;
                    frameId = ((header[4] & 0x00ff) << 24) | ((header[3] & 0x00ff) << 16) |
                            ((header[2] & 0x00ff) << 8) | (header[1] & 0xff);
                    frameSize = ((header[8] & 0x00ff) << 24) | ((header[7] & 0x00ff) << 16) |
                            ((header[6] & 0x00ff) << 8) | (header[5] & 0xff);
                    packetsNumber = header[9] & 0xff;
                    packetId = (header[10] & 0xff);
                    packetSize = ((header[14] & 0xff) << 8) | (header[13] & 0xff);  //Δεν χρειάζεται, θα είναι fix 1024bytes για όλα τα πακέτα
                    localFrameId = (header[15] & 0xff);

                    /*if(packetId == packetsNumber)
                    {

                    }*/
                    if (localFrameId >= maxImagesStored || localFrameId < 0) {
                        //The other values from the header can be used so as the frame be restored, for example we
                        //can compare the values frameId or packetId or frameSize with the values of the store frameObject and find
                        //the right location of the packet, for the moment we just skip this step and we implement this in the future
                        continue;
                    }
                    if (imagesObj[localFrameId].compareHeaders(header) != 0) continue;
                    imagesObj[localFrameId].initiateFrame(packetsNumber, frameId, frameSize, localFrameId);
                    imagesObj[localFrameId].addDataToBuffer(currentPacketData, currentPacketLength, packetId);
                    if (imagesObj[localFrameId].frameFilled() && lastFrameInQueue < frameId) {
                        lastFrameInQueue = frameId;
                        imagesQueue.add(imagesObj[localFrameId]);
                        //Clear the frames buffer which are between 2 consecutive completed frames
                        if (localFrameId >= prevLocalFrameId) {
                            for (int i = prevLocalFrameId; i <= localFrameId; i++)
                                imagesObj[i].initiateFrame();
                        } else {
                            for (int i = prevLocalFrameId; i < maxImagesStored; i++)
                                imagesObj[i].initiateFrame();

                            for (int i = 0; i < localFrameId; i++)
                                imagesObj[i].initiateFrame();
                        }
                        prevLocalFrameId = localFrameId;
                    }


                    //System.arraycopy( currentPacketData, 0, imagesObj[localFrameId].buffer, destPos, currentPacketLength);
                    //Save photo
                    FrameReceivedObject imgObj = imagesQueue.poll();
                    if (imgObj != null) {

                        File photo = new File(Environment.getExternalStorageDirectory(),
                                "photo.jpeg");

                        if (photo.exists()) {
                            photo.delete();
                        }

                        try {
                            FileOutputStream fos = new FileOutputStream(photo.getPath());

                            fos.write(Arrays.copyOfRange(imgObj.buffer, 0, imgObj.frameSize));
                            String s = String.valueOf(fos.getChannel().size());
                            fos.close();

                            Bitmap bitmap = BitmapFactory.decodeByteArray(imgObj.buffer, 0, imgObj.frameSize);
                            Log.d("Myti", "frameSize " + frameSize);
                            if (bitmap != null) {
                                imageView.setImageBitmap(bitmap);
                            }
                        } catch (IOException e) {
                            Log.e("Myti", "Exception in photoCallback", e);
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
        int maxObjectLength = 20000;    //Max size 20kbytes
        byte[] buffer = new byte[maxObjectLength];
        int objLength = 0;
        int packetsNumber = -1;
        int frameId = -1;
        int frameSize = -1;
        int localFrameId = -1;

        public byte compareHeaders(byte[] header) {
            int crnFrameId = ((header[4] & 0x00ff) << 24) | ((header[3] & 0x00ff) << 16) |
                    ((header[2] & 0x00ff) << 8) | (header[1] & 0xff);
            int crnFrameSize = ((header[8] & 0x00ff) << 24) | ((header[7] & 0x00ff) << 16) |
                    ((header[6] & 0x00ff) << 8) | (header[5] & 0xff);
            int crnPacketsNumber = header[9] & 0xff;
            //packetId = (header[10]& 0xff) ;
            int crnLocalFrameId = (header[15] & 0xff);

            if (frameId == -1) {
                frameId = crnFrameId;
            } else if (frameId != crnFrameId) {
                initiateFrame();
                return 1;
            }

            if (frameSize == -1) {
                if (crnFrameSize < maxObjectLength) {
                    frameSize = crnFrameSize;
                } else {
                    initiateFrame();
                    return 5;
                }
            } else if (frameSize != crnFrameSize) {
                return 2;
            }

            if (packetsNumber == -1) {
                if (crnPacketsNumber * packetsLength < maxObjectLength) {
                    packetsNumber = crnPacketsNumber;
                } else {
                    initiateFrame();
                    return 6;
                }
            } else if (packetsNumber != crnPacketsNumber) {
                return 3;
            }

            if (localFrameId == -1) {
                if (crnLocalFrameId < maxImagesStored && crnLocalFrameId >= 0) {
                    localFrameId = crnLocalFrameId;
                } else {
                    initiateFrame();
                    return 7;
                }
            } else if (localFrameId != crnLocalFrameId) {
                return 4;
            }

            //Same headers
            return 0;
        }

        public byte addDataToBuffer(byte[] packetData, int packetLength, int packetId) {
            if (packetId * packetLength > maxObjectLength || packetLength != packetsLength)
                return 0;
            System.arraycopy(packetData, 0, buffer, packetId * packetLength, packetLength);
            objLength += packetLength;
            packetsNumber++;
            return 1;
        }

        public boolean frameFilled() {
            if (objLength == frameSize) return true;

            return false;
        }

        public void initiateFrame() {
            objLength = 0;
            packetsNumber = -1;
            frameId = -1;
            frameSize = -1;
            localFrameId = -1;
        }

        public void initiateFrame(int packetsNumber, int frameId, int frameSize, int localFrameId) {
            if (localFrameId == -1) {
                this.packetsNumber = packetsNumber;
                this.frameId = frameId;
                this.frameSize = frameSize;
                this.localFrameId = localFrameId;
            }
        }


    }

    public class TestSendDataCommunicationThread extends AppCompatActivity implements Runnable {
        private Context context;
        public DatagramSocket phoneSocketSendDataTest;
        public int phonePortSendDataTest;
        byte headerLen = 16 ;
        int packetLen = 1000;
        private byte buf[] = new byte[packetLen + headerLen];
        private DatagramPacket phoneDpReceive = new DatagramPacket(buf, buf.length);
        File root = Environment.getExternalStorageDirectory();

        int maxImageSize = 200000;
        byte[][] imageAll = new byte[2][maxImageSize];
        byte packetType;
        static final byte imageType = 0;
        static final byte audioType = 1;
        int packetSize;
        int frameId;
        int packetsNumber;
        int packetId;
        int frameSize;
        int[] prevPacketSize = {0, 0};
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
                            byte dataImages[][] = new byte[3][];
                            try {

                                //Drawable drawable = getResources().getDrawable(getResources().getIdentifier("img1.jpeg", "drawable", getPackageName()));
                                for (int i = 0; i < 3; i++) {
                                    String fnm = "p" + String.valueOf(i + 1); //  this is image file name
                                    String PACKAGE_NAME = context.getPackageName();
                                    int imgId = context.getResources().getIdentifier(PACKAGE_NAME + ":drawable/" + fnm, null, null);
                                    Bitmap bMap = BitmapFactory.decodeResource(context.getResources(), imgId);
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    bMap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
                                    dataImages[i] = baos.toByteArray();

                                    DatagramPacket dp = new DatagramPacket(dataImages[0], dataImages[0].length, phoneIpReceiveDataTest, phonePortReceiveDataTest);
                                    phoneSocketSendDataTest.send(dp);
                                }
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


        public void sendDataUdp(byte[] frame, long framSize, int frameCount, byte localFrameId) {

            byte packetsNumber = 0;  //The number of packets after splitting all the frame
            byte packetId = 0;       //The ID number(counter) of the current packet
            short lastPacketLen = 0;     //The last packet has usually smaller length than other because is truncated
            short currentPacketLen;
            short packetOffset = 0;
            byte[] txBuffer = new byte[headerLen+packetLen];
            long frameId = frameCount;  //The ID number(counter) of the current frame(all picture)

            currentPacketLen = (short) packetLen;
            packetsNumber = (byte)(Math.floor(frameSize/packetLen) + frameSize%packetLen==0?0:1);
            lastPacketLen = (short)(frameSize - packetLen*(packetsNumber-1));

            while(packetId<packetsNumber){
                if(packetId == packetsNumber-1) currentPacketLen = lastPacketLen;

                packetOffset = (short)(packetId*packetLen);
                //Header construction
                txBuffer[0] = 0;    //Image type
                txBuffer[1] = (byte)(frameId>>24 & 0xff);
                txBuffer[2] = (byte)(frameId>>16 & 0xff);
                txBuffer[3] = (byte)(frameId>>8 & 0xff);
                txBuffer[4] = (byte)(frameId & 0xff);
                txBuffer[5] = (byte)(frameSize>>24 & 0xff);
                txBuffer[6] = (byte)(frameSize>>16 & 0xff);
                txBuffer[7] = (byte)(frameSize>>8 & 0xff);
                txBuffer[8] = (byte)(frameSize & 0xff);
                txBuffer[9] = packetsNumber;
                txBuffer[10] = packetId;
                txBuffer[11] = (byte)(packetLen>>8 & 0xff);
                txBuffer[12] = (byte)(packetLen & 0xff);
                txBuffer[13] = (byte)(currentPacketLen>>8 & 0xff);
                txBuffer[14] = (byte)(currentPacketLen & 0xff);
                txBuffer[15] = (byte) localFrameId;

                // Data
                memcpy(&txBuffer[PACKETHEADERLEN], frame + packetOffset, currentPacketLen);

            }

            DatagramPacket dp = new DatagramPacket(txBuffer, headerLen+packetLen, phoneIpReceiveDataTest, phonePortReceiveDataTest);
            try {
                phoneSocketSendDataTest.send(dp);
            }catch (Exception e){
                Log.d("Myti", "Exception server not ip for client");
                e.printStackTrace();
            }

        }

    }
}
