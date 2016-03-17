package com.mmlab.broadcast.controller;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.onionnetworks.fec.FECCode;
import com.onionnetworks.fec.FECCodeFactory;
import com.onionnetworks.util.Buffer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;

public class BroadcastService {

    private static final String TAG = BroadcastService.class.getName();

    private int BROADCAST_PORT = 65303;
    private int CHUNCK_SIZE = 10240;

    private static final int MAX_SESSION_NUMBER = 255;
    private static final int MAX_GROUP_NUMBER = 255;
    private static final int MAX_ID_NUMBER = 255;

    private static final int MAX_PACKETS = 255 * 255;
    private static final int SESSION_START = 128;
    private static final int SESSION_END = 64;

    private static final int HEADER_SIZE = 52;
    private static final int DATA_SIZE = 512;
    private static int DATAGRAM_MAX_SIZE = HEADER_SIZE + DATA_SIZE;

    public static final int TYPE_TEXT = 0;
    public static final int TYPE_URL = 1;
    public static final int TYPE_FILE = 2;

    private EventHandler mEventHandler;

    private ActionHandler mActionHandler;

    private Context mContext;

    private WifiManager mWifiManager;

    private WifiManager.MulticastLock mMulticastLock;

    private Receiver receiver;

    private ArrayList<Sender> senders = new ArrayList<>();

    public BroadcastService(Context context) {
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }

        HandlerThread handlerThread;
        handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        if ((looper = handlerThread.getLooper()) != null) {
            mActionHandler = new ActionHandler(this, looper);
        } else {
            mActionHandler = null;
        }

        this.mContext = context;
        this.mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        this.mMulticastLock = mWifiManager.createMulticastLock("multicast.test");
    }

    public void start() {
        mMulticastLock.acquire();
    }

    public void stop() {
        mMulticastLock.release();

        if (mEventHandler != null)
            mEventHandler.removeCallbacksAndMessages(null);

        if (mActionHandler != null)
            mActionHandler.removeCallbacksAndMessages(null);
    }

    public void release() {

    }

    public void send(int type, String input, boolean enabled, int magnification) {
        Sender sender = new Sender(type, input);
        if(TYPE_URL == type || TYPE_FILE == type)
            sender.setFileName(input.substring(input.lastIndexOf("/") + 1, input.length()));
        else
            sender.setFileName("poi.txt");
        sender.setFECEnabled(enabled);
        sender.setMagnification(magnification);
        senders.add(sender);
        sender.execute();
    }

    public void receive(boolean enabled) {
        if (receiver == null) {
            receiver = new Receiver();
            receiver.setFECEnabled(enabled);
            receiver.start();
        } else {
            receiver.interrupt();
            receiver = new Receiver();
            receiver.setFECEnabled(enabled);
            receiver.start();
        }
    }

    private int sender_idNumber;
    private int sender_sessionNumber;
    private int sender_groupNumber;
    private int sender_groups;

    private class Sender extends AsyncTask<Void, Void, Void> {

        private final String TAG = Sender.class.getName();

        private static final String DEFAULT_FILENAME = "file.jpg";

        private DatagramSocket datagramSocket;

        private InetAddress inetAddress;

        private String fileName;

        private boolean isFEC = false;

        private double magnification;

        private int type = 0;

        private String input;

        public Sender(int type, String input) {
            this.type = type;
            this.input = input;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public void setFECEnabled(boolean enabled) {
            this.isFEC = enabled;
        }

        public void setMagnification(double magnification) {
            this.magnification = magnification;
        }

        protected Void doInBackground(Void... params) {
            try {
                datagramSocket = new DatagramSocket(null);
                datagramSocket.setReuseAddress(true);
                datagramSocket.bind(new InetSocketAddress(BROADCAST_PORT));
                datagramSocket.setBroadcast(true);

                String broadcastAddress = getBroadcast();
                Log.d(TAG, broadcastAddress);

                inetAddress = InetAddress.getByName(broadcastAddress);

                send();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (datagramSocket != null) {
                    datagramSocket.close();
                }
            }
            return null;
        }

        private void send() {
            sender_groupNumber = 0;

            if (type == TYPE_TEXT) {
                String string = input;
                byte[] data = string.getBytes();
                int dataLength = data.length;
                int k = (int) Math.ceil(dataLength / (float) CHUNCK_SIZE);
                sender_groups = k;
                for (int i = 0; i < k; ++i) {
                    if (i == (k - 1)) {
                        if (isFEC)
                            fecSendBytes(Arrays.copyOfRange(data, i * CHUNCK_SIZE, dataLength - i * CHUNCK_SIZE));
                        else
                            norSendBytes(Arrays.copyOfRange(data, i * CHUNCK_SIZE, dataLength - i * CHUNCK_SIZE));
                    } else {
                        if (isFEC)
                            fecSendBytes(Arrays.copyOfRange(data, i * CHUNCK_SIZE, CHUNCK_SIZE));
                        else
                            norSendBytes(Arrays.copyOfRange(data, i * CHUNCK_SIZE, CHUNCK_SIZE));
                    }
                    sender_groupNumber++;
                }
            } else if (type == TYPE_URL) {
                try {
                    String sUrl = input;
//                    fileName = sUrl.substring(sUrl.lastIndexOf("/") + 1, sUrl.length());
//                    Log.d(TAG, "fileName : " + fileName);
//                    fileName = "file.jpg";
                    URL url = new URL(sUrl);
                    URLConnection conn = url.openConnection();
                    conn.connect();

                    InputStream is = conn.getInputStream();
                    int len = CHUNCK_SIZE, rlen;
                    byte[] buffer = new byte[CHUNCK_SIZE];
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    while ((rlen = is.read(buffer)) != -1) {
                        bos.write(buffer, 0, rlen);
                    }
                    byte[] data = bos.toByteArray();
                    int dadada = data.length;

                    sender_groups = (int) Math.ceil((float) dadada / (float) CHUNCK_SIZE);
                    Log.d(TAG, "length : " + dadada + "  groups:" + sender_groups);
                    for (int i = 0; i < sender_groups; i++) {
                        Log.d(TAG, "current group :" + i);
                        int index = i * CHUNCK_SIZE;
                        if (i == (sender_groups - 1)) {
                            if (isFEC)
                                fecSendBytes(Arrays.copyOfRange(data, index, index + dadada - i * CHUNCK_SIZE));
                            else
                                norSendBytes(Arrays.copyOfRange(data, index, index + dadada - i * CHUNCK_SIZE));
                        } else {
                            if (isFEC) {
                                fecSendBytes(Arrays.copyOfRange(data, index, index + CHUNCK_SIZE));
                            } else
                                norSendBytes(Arrays.copyOfRange(data, index, index + CHUNCK_SIZE));
                        }
                        sender_groupNumber++;
                    }
                } catch (Exception e) {
                    Log.d(TAG, e.toString(), e);
                }
            } else if (type == TYPE_FILE) {
                File file = new File(input);
                FileInputStream fileInputStream = null;
                try {
                    fileInputStream = new FileInputStream(file);
                    sender_groups = (int) Math.ceil((float) file.length() / (float) CHUNCK_SIZE);
                    byte[] bytes = new byte[CHUNCK_SIZE];
                    int readBytes;
                    while ((readBytes = fileInputStream.read(bytes, 0, CHUNCK_SIZE)) != -1) {
                        if (isFEC)
                            fecSendBytes(bytes);
                        else
                            norSendBytes(bytes);
                        sender_groupNumber++;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (fileInputStream != null) {
                        try {
                            fileInputStream.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }

            sender_idNumber = sender_idNumber < MAX_ID_NUMBER ? ++sender_idNumber : 0;
        }

        private void norSendBytes(byte[] bytes) {
            int bytesLength = bytes.length;
            int k = (int) Math.ceil(bytesLength / (float) DATA_SIZE);

//            byte[] source = new byte[k * DATA_SIZE];
//            System.arraycopy(bytes, 0, source, 0, bytesLength);
//            int sourceLength = source.length;

            DatagramPacket sendPacket;
            for (int i = 0; i < k; i++) {

                int flags = 0;
                flags = i == 0 ? flags | SESSION_START : flags;
                flags = (i + 1) * DATA_SIZE > bytesLength ? flags | SESSION_END : flags;

                int size = (flags & SESSION_END) != SESSION_END ? DATA_SIZE : bytesLength - i * DATA_SIZE;
                byte[] sendData = new byte[HEADER_SIZE + DATA_SIZE];
                sendData[0] = (byte) flags;
                sendData[1] = (byte) sender_sessionNumber;
                sendData[2] = (byte) (k >> 8);
                sendData[3] = (byte) k;
                sendData[4] = (byte) (DATA_SIZE >> 8);
                sendData[5] = (byte) DATA_SIZE;
                sendData[6] = (byte) (i >> 8);
                sendData[7] = (byte) i;
                sendData[8] = (byte) (size >> 8);
                sendData[9] = (byte) size;
                sendData[10] = (byte) sender_idNumber;
                sendData[11] = (byte) sender_groups;
                sendData[12] = (byte) sender_groupNumber;

                byte[] txtBytes = fileName.getBytes();
                sendData[13] = (byte) txtBytes.length;
                System.arraycopy(txtBytes, 0, sendData, 14, txtBytes.length);

                byte[] double_bytes = new byte[8];
                ByteBuffer.wrap(double_bytes).putDouble(magnification);
                System.arraycopy(double_bytes, 0, sendData, 44, double_bytes.length);

                System.arraycopy(bytes, i * DATA_SIZE, sendData, HEADER_SIZE, size);

                sendPacket = new DatagramPacket(sendData, DATAGRAM_MAX_SIZE, inetAddress, BROADCAST_PORT);

                try {
                    datagramSocket.send(sendPacket);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            sender_sessionNumber = sender_sessionNumber < MAX_SESSION_NUMBER ? ++sender_sessionNumber : 0;
        }

        private void fecSendBytes(byte[] data) {
            int dataLength = data.length;
            int k = (int) Math.ceil(dataLength / (float) DATA_SIZE);
            int n = (int) ((double) k * magnification);

            byte[] source = new byte[k * DATA_SIZE]; //this is our source file
            Arrays.fill(source, (byte) 0);
            System.arraycopy(data, 0, source, 0, dataLength);

            //NOTE: The source needs to split into k*packetsize sections
            //So if your file is not of the right size you need to split
            //it into groups.  The final group may be less than
            //k*packetsize, in which case you must pad it until you read
            //k*packetsize.  And send the length of the file so that you
            //know where to cut it once decoded.

            //this will hold the encoded file
            byte[] repair = new byte[n * DATA_SIZE];

            //These buffers allow us to put our data in them they
            //reference a packet length of the file (or at least will once
            //we fill them)
            Buffer[] sourceBuffer = new Buffer[k];
            Buffer[] repairBuffer = new Buffer[n];

            for (int i = 0; i < sourceBuffer.length; i++)
                sourceBuffer[i] = new Buffer(source, i * DATA_SIZE, DATA_SIZE);

            for (int i = 0; i < repairBuffer.length; i++)
                repairBuffer[i] = new Buffer(repair, i * DATA_SIZE, DATA_SIZE);

            //When sending the data you must identify what it's index was.
            //Will be shown and explained later
            int[] repairIndex = new int[n];

            for (int i = 0; i < repairIndex.length; i++)
                repairIndex[i] = i;

            //create our fec code
            FECCode fec = FECCodeFactory.getDefault(mContext).createFECCode(k, n);

            //encode the data
            fec.encode(sourceBuffer, repairBuffer, repairIndex);
            //encoded data is now contained in the repairBuffer/repair byte array

            //From here you can send each 'packet' of the encoded data, along with
            //what repairIndex it has.  Also include the group number if you had to
            //split the file
            dataLength = n * DATA_SIZE;

            DatagramPacket segmentPacket;
            for (int i = 0; i < n; i++) {

                int flags = 0;
                flags = i == 0 ? flags | SESSION_START : flags;
                flags = (i + 1) * DATA_SIZE > dataLength ? flags | SESSION_END : flags;

                int size = (flags & SESSION_END) != SESSION_END ? DATA_SIZE : dataLength - i * DATA_SIZE;
                byte[] head = new byte[HEADER_SIZE + size];
                head[0] = (byte) flags;
                head[1] = (byte) sender_sessionNumber;
                head[2] = (byte) (k >> 8);
                head[3] = (byte) k;
                head[4] = (byte) (DATA_SIZE >> 8);
                head[5] = (byte) DATA_SIZE;
                head[6] = (byte) (i >> 8);
                head[7] = (byte) i;
                head[8] = (byte) (size >> 8);
                head[9] = (byte) size;
                head[10] = (byte) sender_idNumber;
                head[11] = (byte) sender_groups;
                head[12] = (byte) sender_groupNumber;

                byte[] txtBytes = fileName.getBytes();
                head[13] = (byte) txtBytes.length;
                System.arraycopy(txtBytes, 0, head, 14, txtBytes.length);

                byte[] double_bytes = new byte[8];
                ByteBuffer.wrap(double_bytes).putDouble(magnification);
                System.arraycopy(double_bytes, 0, head, 44, double_bytes.length);

                System.arraycopy(repairBuffer[i].getBytes(), 0, head, HEADER_SIZE, repairBuffer[i].getBytes().length);

                // int index = i * DATAGRAM_MAX_SIZE;

                if (i == (n - 1)) {
                    segmentPacket = new DatagramPacket(head, DATAGRAM_MAX_SIZE, inetAddress, BROADCAST_PORT);
                } else {
                    segmentPacket = new DatagramPacket(head, DATAGRAM_MAX_SIZE, inetAddress, BROADCAST_PORT);
                }

                // suspend=generator.nextDouble()*10*1;
                try {
                    datagramSocket.send(segmentPacket);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                // Thread.sleep((int)suspend);
            }

        /* Increase session number */
            sender_sessionNumber = sender_sessionNumber < MAX_SESSION_NUMBER ? ++sender_sessionNumber : 0;
        }
    }

    private class Receiver extends Thread {

        private final String TAG = Receiver.class.getName();

        private DatagramSocket datagramSocket = null;

        private InetAddress inetAddress = null;

        private boolean isFEC = false;

        public Receiver() {

        }

        public void setFECEnabled(boolean enabled) {
            this.isFEC = enabled;
        }

        public void run() {
            try {
                datagramSocket = new DatagramSocket(null);
                datagramSocket.setReuseAddress(true);
                datagramSocket.bind(new InetSocketAddress(BROADCAST_PORT));
                datagramSocket.setBroadcast(true);

                String broadcastAddress = getBroadcast();
                Log.d(TAG, "broadcastAddress : " + broadcastAddress);

                inetAddress = InetAddress.getByName(broadcastAddress);

                while (!Thread.currentThread().isInterrupted()) {
                    byte[] receiveData = new byte[DATAGRAM_MAX_SIZE];
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, DATAGRAM_MAX_SIZE);
                    receivePacket.setLength(DATAGRAM_MAX_SIZE);
                    datagramSocket.receive(receivePacket);

                    Message message = mActionHandler.obtainMessage(ACTION_RECEIVE_ACTION, receivePacket);
                    message.arg1 = !isFEC ? 0 : 1;
                    mActionHandler.sendMessage(message);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (datagramSocket != null) {
                    datagramSocket.close();
                }
            }
        }

        public void interrupt() {
            super.interrupt();

            try {
                datagramSocket.close();
            } catch (Exception e) {
                Log.d(TAG, e.toString(), e);
            }
        }
    }

    public void norRecv(String fileName, byte[] ddd, int[] dddIndex) {
        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(Environment.getExternalStorageDirectory() + File.separator + fileName, true);
//            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
            fileOutputStream.write(ddd, 0, ddd.length);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.flush();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }

                try {
                    fileOutputStream.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

    public void fecRecv(String fileName, byte[] ddd, int[] dddIndex) {
        //create our fec code
        FECCode fec = FECCodeFactory.getDefault(mContext).createFECCode(receiver_recvK, receiver_recvN);

        //We only need to store k, packets received
        //Don't forget we need the index value for each packet too
        Buffer[] receiverBuffer = new Buffer[receiver_recvK];
        int[] receiverIndex = new int[receiver_recvK];

        //this will store the received packets to be decoded
        byte[] received = new byte[receiver_recvK * DATA_SIZE];

        System.arraycopy(ddd, 0, received, 0, receiver_recvK * DATA_SIZE);
        System.arraycopy(dddIndex, 0, receiverIndex, 0, receiver_recvK);

        //create our Buffers for the encoded data
        for (int i = 0; i < receiver_recvK; i++)
            receiverBuffer[i] = new Buffer(received, i * DATA_SIZE, DATA_SIZE);

        //finally we can decode
        fec.decode(receiverBuffer, receiverIndex);

//        Bitmap bitmap = BitmapFactory.decodeByteArray(received, 0, received.length);
        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(Environment.getExternalStorageDirectory() + File.separator + fileName, true);
//            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
            fileOutputStream.write(received, 0, received.length);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.flush();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }

                try {
                    fileOutputStream.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

    private static final int EVENT_FINISH_ACTION = 0;
    private static final int EVENT_RECEIVE_ACTION = 1;

    private class EventHandler extends Handler {

        private final String TAG = EventHandler.class.getName();

        private BroadcastService mBroadcastService;

        public EventHandler(BroadcastService broadcastService, Looper looper) {
            super(looper);
            this.mBroadcastService = broadcastService;
        }

        public void handleMessage(Message msg) {
            super.handleMessage(msg);
        }
    }

    private static final int ACTION_RECEIVE_ACTION = 0;

    int receiver_idNumber = -1;
    int receiver_currentSession = -1;
    int currentGroup = -1;
    int receiver_currentId = -1;
    int receiver_slicesStored = 0;
    boolean receiver_endFirst = true;
    int[] receiver_slicesCol = null;
    int[] receiver_sliceConsist = null;
    byte[] receiver_imageData = null;
    boolean receiver_sessionAvailable = false;
    int receiver_recvK;
    int receiver_recvN;
    boolean receiver_finished = false;

    private class ActionHandler extends Handler {

        private final String TAG = ActionHandler.class.getName();

        private BroadcastService mBroadcastService;

        public ActionHandler(BroadcastService broadcastService, Looper looper) {
            super(looper);
            this.mBroadcastService = broadcastService;
        }

        public void handleMessage(Message msg) {

            boolean isFEC = msg.arg1 != 0;

            switch (msg.what) {
                case ACTION_RECEIVE_ACTION:
                    DatagramPacket datagramPacket = (DatagramPacket) msg.obj;
                    byte[] data = datagramPacket.getData();

                    short session = (short) (data[1] & 0xff);
                    int slices = (int) ((data[2] & 0xff) << 8 | (data[3] & 0xff));
                    int maxPacketSize = (int) ((data[4] & 0xff) << 8 | (data[5] & 0xff)); // mask the sign bit
                    int slice = (int) ((data[6] & 0xff) << 8 | (data[7] & 0xff)); // mask the sign bit
                    int size = (int) ((data[8] & 0xff) << 8 | (data[9] & 0xff)); // mask the sign bit
                    short id = (short) (data[10] & 0xff);
                    short sets = (short) (data[11] & 0xff);
                    short set = (short) (data[12] & 0xff);
                    int nameLength = (int) (data[13] & 0xff);

                    Log.d(TAG, "id : " + id);
                    Log.d(TAG, "sets : " + sets);
                    Log.d(TAG, "set : " + set);
                    Log.d(TAG, "session : " + session);
                    Log.d(TAG, "slices : " + slices);
                    Log.d(TAG, "maxPacketSize : " + maxPacketSize);
                    Log.d(TAG, "slice : " + slice);
                    Log.d(TAG, "size : " + size);
                    Log.d(TAG, "nameLength : " + nameLength);

                    String name = new String(Arrays.copyOfRange(data, 14, 14 + nameLength));

                    double magnification = ByteBuffer.wrap(Arrays.copyOfRange(data, 44, 44 + 8)).getDouble();

                    Log.d(TAG, "name : " + name);
                    Log.d(TAG, "magnification : " + magnification);

                    receiver_currentId = id;

                    if (id != receiver_idNumber && receiver_idNumber != -1 && !receiver_finished) {
                        receiver_finished = true;
                        mEventHandler.obtainMessage(EVENT_FINISH_ACTION).sendToTarget();
                    }

                    if (session != receiver_currentSession) {
                        receiver_finished = false;
                        receiver_currentSession = session;
                        receiver_recvK = slices;
                        if (isFEC)
                            receiver_recvN = (int) ((double) receiver_recvK * magnification);
                        else
                            receiver_recvN = receiver_recvK;
                        receiver_slicesStored = 0;
                        receiver_imageData = new byte[slices * maxPacketSize];
                        receiver_slicesCol = new int[slices];
                        receiver_sliceConsist = new int[receiver_recvN];
                        receiver_sessionAvailable = true;
                    }

                    if (receiver_sessionAvailable && session == receiver_currentSession && receiver_slicesStored < receiver_recvK) {
                        if (receiver_slicesCol != null && receiver_sliceConsist[slice] == 0) {
                            receiver_sliceConsist[slice] = 1;
                            receiver_slicesCol[receiver_slicesStored] = slice;
                            if (isFEC) {
                                System.arraycopy(data, HEADER_SIZE, receiver_imageData, receiver_slicesStored * maxPacketSize, size);
                            } else {
                                System.arraycopy(data, HEADER_SIZE, receiver_imageData, slice * maxPacketSize, size);
                            }
                            receiver_slicesStored++;

                            if (receiver_slicesStored == receiver_recvK) {
                                Log.d(TAG, "We did it!!!");
                                if (set == (sets - 1)) {
                                    receiver_finished = true;
                                    mEventHandler.obtainMessage(EVENT_FINISH_ACTION).sendToTarget();
                                }

                                if (!name.contains("txt")) {
                                    if (!isFEC)
                                        norRecv(name, receiver_imageData, receiver_slicesCol);
                                    else {
                                        fecRecv(name, receiver_imageData, receiver_slicesCol);
                                    }
                                }
                                mEventHandler.obtainMessage(EVENT_RECEIVE_ACTION).sendToTarget();
                            }
                        }
                    }

                    break;
                default:
            }
            super.handleMessage(msg);
        }
    }

    public static String getBroadcast() throws SocketException {
        System.setProperty("java.net.preferIPv4Stack", "true");
        for (Enumeration<NetworkInterface> niEnum = NetworkInterface.getNetworkInterfaces(); niEnum.hasMoreElements(); ) {
            NetworkInterface ni = niEnum.nextElement();

            if (!ni.isLoopback()) {
                for (InterfaceAddress interfaceAddress : ni.getInterfaceAddresses()) {
                    if (interfaceAddress.getBroadcast() != null) {
                        return interfaceAddress.getBroadcast().toString().substring(1);
                    }
                }
            }
        }
        return null;
    }
}

