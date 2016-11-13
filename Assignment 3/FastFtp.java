
/*public class cpsc441.a3.Utils {
  public cpsc441.a3.Utils();
  public static java.net.DatagramPacket makePacket(cpsc441.a3.Segment, java.net.InetAddress, int);
  public static java.net.DatagramPacket makePacket(cpsc441.a3.Segment, java.net.SocketAddress);
  public static java.net.DatagramPacket makePacket(cpsc441.a3.Segment, java.lang.String, int) throws java.net.UnknownHostException;
  public static java.net.DatagramPacket makePacket(int);
  public static java.net.DatagramPacket makePacket();
  public static cpsc441.a3.Segment receiveSegment(java.net.DatagramSocket, java.net.SocketAddress) throws java.io.IOException;
  public static void sendSegment(java.net.DatagramSocket, java.net.SocketAddress, cpsc441.a3.Segment) throws java.io.IOException;
}*/

import java.io.*;
import java.util.logging.*;
import java.util.TimerTask;
import java.util.Timer;
import java.net.Socket;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.SocketException;
import cpsc441.a3.*;

/**
 * FastFtp Class
 *
 * FastFtp implements a basic FTP application based on UDP data transmission.
 * The main mehtod is send() which takes a file name as input argument and send the file
 * to the specified destination host.
 *
 */
public class FastFtp {
    /**
     * Logging framework
     */
    private static final Logger LOGGER = Logger.getLogger(FastFtp.class.getName());

    /**
     * Window Size (segs)
     */
    private int windowSize;

    /**
     * Segment number
     */
    private int segNum;

    /**
     * Segment number of the upper segment of the window
     */
    private int upperWindow;

    /**
     * Segment number of the lowest segment of the window
     */
    private int lowerWindow;

    /**
     * TimeoutLength (ms)
     */
    private int timeoutLength;

    /**
     * The window
     */
    private TxQueue window;
    /**
     * Control socket
     */
    private Socket tcpConnection;

    /**
     * Data socket
     */
    private DatagramSocket udpConnection;

    /**
     * Streams for socket IO
     */
    private DataInputStream tcpIn;
    private DataOutputStream tcpOut;

    /**
     * Retransmission timer
     */
    private Timer rtTimer;

    /**
     * Handler class for when the rtoTimer expires
     */
    class TimeoutHandler extends TimerTask {
        /**
         * Default constructor
         */
        public TimeoutHandler () {}

        /**
         * Gets called when the timer expires
         */
        public void run () {
            processTimeout();
        }
    };

    /**
     * Thread in charge of recieving ACKS
     */
    class RecieverThread extends Thread {
        /**
         * UDP connection
         */
        private DatagramSocket conn;

        /**
         * Termination flag
         */
        private boolean keepGoing = true;

        /**
         * Default constructor
         */
        public RecieverThread (DatagramSocket conn) {
            this.conn = conn;
            try {
                conn.setSoTimeout(timeoutLength);
            } catch (SocketException e) {
                LOGGER.severe("Could not set UDP timeout");
                LOGGER.severe(e.getMessage());
                System.exit(1);
            }
        }

        /**
         * Starts the thread
         */
        public void run () {
            DatagramPacket pkt = new DatagramPacket(new byte[Segment.MAX_SEGMENT_SIZE], Segment.MAX_SEGMENT_SIZE);
            while (keepGoing) {
                try {
                    conn.receive(pkt);
                    processACK(new Segment(pkt));
                } catch (SocketTimeoutException e) {
                    // OK - check the while condition and try again
                    // LOGGER.finest("UDP timed out - check while loop");
                    continue;
                } catch (PortUnreachableException e) {
                    LOGGER.severe("The server is not on a reachable port");
                    LOGGER.severe(e.getMessage());
                } catch (IOException e) {
                    LOGGER.severe("UDP read failed");
                    LOGGER.severe(e.getMessage());
                    System.exit(1);
                }
            }
        }

        /**
         * Asks nicely for the thread to stop
         */
        public void stopRunning () {
            keepGoing = false;
        }
    }

    /**
     * Constructor to initialize the program
     *
     * @param windowSize	Size of the window for Go-Back_N (in segments)
     * @param rtoTimer		The time-out interval for the retransmission timer (in milli-seconds)
     */
    public FastFtp (int windowSize, int rtoTimer) {
        this.windowSize = windowSize;
        timeoutLength = rtoTimer;
        rtTimer = new Timer(true);
        window = new TxQueue(windowSize);
        segNum = 0;
        upperWindow = 0;
        lowerWindow = 0;
    }


    /**
     * Sends the specified file to the specified destination host:
     * 1. send file name and receiver server confirmation over TCP
     * 2. send file segment by segment over UDP
     * 3. send end of transmission over tcp
     * 3. clean up
     *
     * @param serverName	Name of the remote server
     * @param serverPort	Port number of the remote server
     * @param fileName		Name of the file to be trasferred to the rmeote server
     */
    public void send (String serverName, int serverPort, String fileName) {
        // check the file is OK
        LOGGER.finer("Checking file: fileName");
        File file = new File(fileName);
        if (file.exists() && file.isFile()) {
            LOGGER.finer("File exists: " + fileName);
            if (file.length() == 0) {
                LOGGER.severe("File is of length 0. Aborting");
                System.exit(1);
            }
            LOGGER.finest("File has " + file.length() + " bytes");
            // TCP connection
            try {
                tcpConnection = new Socket(serverName, serverPort);
                tcpConnection.setSoTimeout(10000);
                tcpIn = new DataInputStream(tcpConnection.getInputStream());
                tcpOut = new DataOutputStream(tcpConnection.getOutputStream());
            } catch (Exception e) {
                LOGGER.severe("Could not start control socket");
                LOGGER.severe(e.getMessage());
                System.exit(1);
            }

            // TCP handshake
            LOGGER.finer("Initiating handshake");
            int code = -1;
            try {
                tcpOut.writeUTF(new String(fileName));
                tcpOut.flush();
                code = tcpIn.readByte();
            } catch (IOException e) {
                LOGGER.severe("Handshake failed");
                LOGGER.severe(e.getMessage());
            }
            if (code == 0) {
                LOGGER.finer("Server ready");
            } else {
                LOGGER.severe("Server responded with error code: " + code);
                try {
                    tcpIn.close();
                    tcpOut.close();
                    tcpConnection.close();
                    System.exit(1);
                } catch (IOException e) {
                    LOGGER.severe("Could not close streams/sockets");
                    LOGGER.severe(e.getMessage());
                    System.exit(1);
                }
            }

            // UDP connection
            try {
                udpConnection = new DatagramSocket(tcpConnection.getLocalPort());
                udpConnection.connect(tcpConnection.getRemoteSocketAddress());
            } catch (Exception e) {
                LOGGER.severe("Could not start data socket");
                LOGGER.severe(e.getMessage());
                System.exit(1);
            }

            // ACK reciever thread
            LOGGER.finer("Starting ACK thread");
            RecieverThread ackThread = new RecieverThread(udpConnection);
            ackThread.start();

            // send the file
            LOGGER.finer("Starting file send");
            FileInputStream fis;
            try {
                fis = new FileInputStream(file);
                LOGGER.finest("Available bytes: " + fis.available());
                byte[] payload = new byte[Segment.MAX_PAYLOAD_SIZE];
                int ret = 0;
                while ((ret = fis.read(payload)) != -1) {
                    LOGGER.finest("Creating segment @ seq " + segNum + " of payload length " + ret);
                    upperWindow = segNum;
                    byte[] pyld = new byte[ret];
                    pyld = payload;
                    Segment seg = new Segment(segNum, pyld);
                    segNum += 1;
                    while (window.isFull()) {
                        Thread.yield();
                    }
                    LOGGER.finest("Window not full, sending segment");
                    processSend(seg);
                    // while (!window.isEmpty() && window.isFull()) {
                    //     Thread.yield();
                    // }
                    LOGGER.finest("Grabbing more data");
                }
                fis.close();
                LOGGER.finest("While loop completed. ret=" + ret);
            } catch (FileNotFoundException e) {
                LOGGER.severe("File not found, but was checked to exist");
                System.exit(1);
            } catch (IOException e) {
                LOGGER.severe(e.getMessage());
            }
            LOGGER.fine("Waiting until window is empty");
            while (!window.isEmpty()) {
                Thread.yield();
            }
            LOGGER.finer("File is sent, ending transmission");
            // send end of transmission (TCP)
            try {
                tcpOut.writeByte(0);
                tcpOut.flush();
            } catch (IOException e) {
                LOGGER.severe("Could not end transmission");
                LOGGER.severe(e.getMessage());
            }

            // cancel timer
            LOGGER.finest("Cancelling timer");
            rtTimer.cancel();

            // shut down ack thread
            ackThread.stopRunning();

            // clean up sockets
            try {
                tcpIn.close();
                tcpOut.close();
                tcpConnection.close();
                udpConnection.close();
            } catch (IOException e) {
                LOGGER.severe("Could not close sockets");
                LOGGER.severe(e.getMessage());
            }
        } else {
            LOGGER.severe("File is not OK; exists:" + file.exists() + "; isfile:" + file.isFile());
            System.exit(1);
        }
    }

    /**
     * Send the segment to the UDP socket
     * @param seg   The segment to send
     */
    public synchronized void processSend (Segment seg) {
        // encapsulate the segment
        DatagramPacket pkt = encapsulateSegment(seg);
        // send the packet
        try {
            LOGGER.fine("SND: SEQ " + seg.getSeqNum());
            udpConnection.send(pkt);
        } catch (IOException e) {
            LOGGER.severe("Could not send segment");
            LOGGER.severe(e.getMessage());
        }
        // add the segment to the transmission queue
        try {
            window.add(seg);
            if (window.size() == 1) {
                // start the timer
                LOGGER.finest("Starting the timer");
                try {
                    rtTimer.schedule(new TimeoutHandler(), timeoutLength);
                } catch (IllegalStateException e) {
                    LOGGER.warning("Timer was cancelled");
                    rtTimer = new Timer(true);
                    rtTimer.schedule(new TimeoutHandler(), timeoutLength);
                }
            }
        } catch (InterruptedException e) {
            LOGGER.severe("Could not add segment to the queue");
            LOGGER.severe(e.getMessage());
        }
    }

    /**
     * Process an ACK segment
     * @param ack   The ACK segment to process
     */
    public synchronized void processACK (Segment ack) {
        LOGGER.fine("RCV: ACK " + ack.getSeqNum());
        LOGGER.fine("Window: " + lowerWindow + " " + upperWindow);
        // if the ack is in the window
        if ((lowerWindow <= (ack.getSeqNum() - 1) && (ack.getSeqNum() - 1) <= upperWindow )) {
            // cancel the timer
            LOGGER.finest("Cancelling timer");
            rtTimer.cancel();
            while (window.element() != null && window.element().getSeqNum() < ack.getSeqNum()) {
                try {
                    lowerWindow = window.element().getSeqNum() + 1;
                    LOGGER.fine("Set lowerWindow to " + lowerWindow);
                    window.remove();
                    LOGGER.fine("Removed SEG " + window.element().getSeqNum() + " from the queue");
                } catch (Exception e) {
                    System.err.println("Exception when window.remove(): " + e);
                    LOGGER.severe("Message: " + e.getMessage());
                }
            }
            LOGGER.fine("Checking window emptyness");
            if (!window.isEmpty()) {
                //start timer
                rtTimer = new Timer(true);
                rtTimer.schedule(new TimeoutHandler(), timeoutLength);
            }
        } else {
            LOGGER.fine("Recieved ACK outside the window: " + ack.getSeqNum() + "(acking seq " + (ack.getSeqNum() - 1) + ")");
        }
    }

    /**
     * Timer handler helper
     */
    public synchronized void processTimeout () {
        LOGGER.fine("TIMEOUT (ACK " + (segNum - windowSize) + " never received)");
        // get list of all pending segs
        Segment[] pending = window.toArray();

        // go through the list and send all segs
        for (Segment seg : pending) {
            try {
                LOGGER.fine("SND: SEQ " + seg.getSeqNum());
                udpConnection.send(encapsulateSegment(seg));
            } catch (IOException e) {
                LOGGER.severe("Could not send segment");
                LOGGER.severe(e.getMessage());
            }
        }
        if (!window.isEmpty()) {
            // start the timer
            rtTimer.cancel();
            rtTimer = new Timer(true);
            rtTimer.schedule(new TimeoutHandler(), timeoutLength);
        }
    }

    public DatagramPacket encapsulateSegment (Segment seg) {
        byte[] segB = seg.getBytes();
        return new DatagramPacket(new byte[segB.length], segB.length, udpConnection.getInetAddress(), udpConnection.getPort());
    }

    /**
     * A simple test driver
     */
    public static void main (String[] args) {
        // initialize logger
        LOGGER.setLevel(Level.FINE);
        ConsoleHandler logHandler = new ConsoleHandler();
        logHandler.setLevel(Level.FINE);
        LOGGER.addHandler(logHandler);
        LOGGER.setUseParentHandlers(false);
        LOGGER.finest("Check");

        int windowSize = 1;//10; //segments
        int timeout = 5000;//100; // milli-seconds

        String serverName = "localhost";
        String fileName = "";
        int serverPort = 0;

        // check for command line arguments
        if (args.length == 3) {
            // either provide 3 paramaters
            serverName = args[0];
            serverPort = Integer.parseInt(args[1]);
            fileName = args[2];
        } else if (args.length == 2) {
            // or just server port and file name
            serverPort = Integer.parseInt(args[0]);
            fileName = args[1];
        } else {
            System.out.println("wrong number of arguments, try again.");
            System.out.println("usage: java FastFtp [serverName] <serverPort> <fileName>");
            System.exit(0);
        }
        LOGGER.fine("Arguments OK");

        LOGGER.fine("Starting FastFtp with windowSize:" + windowSize + ", timeout:" + timeout);
        FastFtp ftp = new FastFtp(windowSize, timeout);

        System.out.printf("sending file \'%s\' to server...\n", fileName);
        ftp.send(serverName, serverPort, fileName);
        System.out.println("file transfer completed.");
    }

}
