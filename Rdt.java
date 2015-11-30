 /**
  * Authors: Carlos Gonzalez, Nicola Pedretti
  * Washington University in St. Louis
  * CSE 473: Introduction to Computer Networks
  * Lab4
  *
  * Reliable Data Transport class.
  *
  *  This class implements a reliable data transport service.
  *  It uses a sliding window protocol, on a packet basis,
  *  with selective repeat.
  *
  *  An application layer thread provides new packet payloads to be
  *  sent using the provided send() method, and retrieves newly arrived
  *  payloads with the receive() method. Each application layer payload
  *  is sent as a separate UDP packet, along with a sequence number and
  *  a type flag that identifies a packet as a data packet or an
  *  acknowledgment. The sequence numbers are 15 bits.
  */

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Rdt implements Runnable {
    
    private int							wSize;				// protocol window
    // size
    private long						timeout;			// retransmission
    // timeout in ns
    private Substrate					sub;				// Substrate object
    // for
    // packet IO
    
    // queues for communicating with source/sink
    private ArrayBlockingQueue<String>	fromSrc;
    private ArrayBlockingQueue<String>	toSnk;
    
    // data structures to handle ack, send, receive packets
    private Packet[]					sendBuffer;
    private long[]						resendTimes;
    private LinkedList<Short>			resendList;
    private String[]					receiveBuffer;
    
    // variables to keep track of sending and receiving buffer statuses
    private short						nextSequenceNumber;
    private short						nextExpectedPacket;
    
    private Thread						myThread;			// local thread for
    // this
    // object
    private boolean						quit;				// used to signal
    // quitting time
    
    /** Initialize a new Rdt object.
     *  @param wSize is the window size used by protocol; the sequence #
     *  space is twice the window size
     *  @param timeout is the time to wait before retransmitting
     *  @param sub is a reference to the Substrate object that this object
     *  uses to handle the socket IO
     */
    Rdt(int wSize, double timeout, Substrate sub) {
        this.wSize = Math.min(wSize, (1 << 14) - 1);
        this.timeout = ((long) (timeout * 1000000000)); // sec to ns
        this.sub = sub;
        
        // create queues for application layer interface
        fromSrc = new ArrayBlockingQueue<String>(1000, true);
        toSnk = new ArrayBlockingQueue<String>(1000, true);
        quit = false;
        
        // initialize data structures to handle ack, send, receive packets
        sendBuffer = new Packet[2 * wSize];
        resendTimes = new long[2 * wSize];
        resendList = new LinkedList<Short>();
        receiveBuffer = new String[wSize];
        
        // initialize variables to keep track of sending and receiving buffer
        // statuses
        nextSequenceNumber = 0;
        nextExpectedPacket = 0;
        
    }
    
    /** Start the Rdt running. */
    public void start() throws Exception {
        myThread = new Thread(this);
        myThread.start();
    }
    
    /** Stop the Rdt.  */
    public void stop() throws Exception {
        quit = true;
        myThread.join();
    }
    
    /** Increment sequence number, handling wrap-around.
     *  @param x is a sequence number
     *  @return next sequence number after x
     */
    private short incr(short x) {
        x++;
        return (x < 2 * wSize ? x : 0);
    }
    
    /** Compute the difference between two sequence numbers,
     *  accounting for "wrap-around"
     *  @param x is a sequence number
     *  @param y is another sequence number
     *  @return difference, assuming x is "clockwise" from y
     */
    private int diff(short x, short y) {
        return (x >= y ? x - y : (x + 2 * wSize) - y);
    }
    
    /** Main thread for the Rdt object.
     *
     *  Inserts payloads received from the application layer into
     *  packets, and sends them to the substrate. The packets include
     *  the number of packets and chars sent so far (including the
     *  current packet). It also takes packets received from
     *  the substrate and sends the extracted payloads
     *  up to the application layer. To ensure that packets are
     *  delivered reliably and in-order, using a sliding
     *  window protocol with the selective repeat feature.
     */
    public void run() {
        long t0 = System.nanoTime();
        long now = 0; // current time (relative to t0)
        
        while (!quit || (numUnAckedPackets() > 0)) {
            now = System.nanoTime() - t0;
            //Every case gets executed independently in its own function.
            
            //Uploads packets to the Sink from Rdt.
            if(uploadOrderedPackets()){
               
            //Process Incoming packets from the Substrate.
            }else if(processIncomingPackets()){
             
            //Manages timeouts of packets that have been sent but no ack had been received.
            }else if(resendTimedoutPackets(now)){
                
            //Send all the packets that the SrcSnk has ready to send unto the substrate.
            }else if(sendReadyPacket(now)){
                
            }else{
                //Sleep if nothing else needs to be done.
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    System.err.println("Rdt:run: " + "sleep exception " + e);
                    System.exit(1);
                }
            }
            
        }
    }
    
    /**
     * If receive buffer has a packet that can be delivered, deliver it to sink
     * @return boolean true if anything is uploaded, false otherwise.
     */
    private boolean uploadOrderedPackets() {
        //Selecting the index of the first packet to upload to Snk.
        short index = (short) (nextExpectedPacket%wSize);
        if(receiveBuffer[index] == null) return false;
        
        
        //Counter used to keep track of how many packets are uploaded.
        int counter =0;
        
        //Upload as many packets as allowable
        while (receiveBuffer[index] != null){
            
            //Attempt to upload, detect if toSnk cannot receive anymore.
            if(!toSnk.offer(receiveBuffer[index])){
                nextExpectedPacket = index;
                System.out.println("Maximum size of toSnk reached.");
                return true;
            }
            counter++;
            
            //Clear the receive buffer and move on to next valid index.
            receiveBuffer[index] = null;
            index = (short) (++index % wSize);
        }
        //Update the nextExpected packet.
        nextExpectedPacket = (short) ((nextExpectedPacket + counter)%(2*wSize));
        return true;
    }
    
    /**
     * If the substrate has an incoming packet get the packet from the substrate and process it.
     * @return boolean false if there is no incoming packet
     */
    private boolean processIncomingPackets() {
        //Check if the substrate has an incomming packet.
        if(!sub.incoming())
            return false;
        
        //Receive a packet from the substrate, and processs it.
        Packet rcvdPacket = sub.receive();
        
        //If the packet is a data packet, prepare and return an ack packet.
        if (rcvdPacket.type == Packet.DATA_TYPE) {
            Packet ack = new Packet();
            ack.seqNum = rcvdPacket.seqNum;
            ack.type = Packet.DATA_TYPE+1;
            waitSub();
            sub.send(ack);
            
            //If the packet is within the window size range, add it to the buffer.
            if(!(diff(rcvdPacket.seqNum,nextExpectedPacket)>wSize)){
                receiveBuffer[rcvdPacket.seqNum % wSize] = rcvdPacket.payload;
            }
        }
        //If the packet is an ack, remove it from our matching buffers.
        else {
            sendBuffer[rcvdPacket.seqNum] = null;
            resendList.removeFirstOccurrence(rcvdPacket.seqNum);
        }
        return true;
    }
    
    /**
     * If the resend timer has expired, re-send the oldest un-acked
     * packet and reset timer
     */
    public boolean resendTimedoutPackets(long now){
        //Checking to see if there is anything to resend.
        if(resendList.isEmpty()){
            return false;
        }
        
        //Computing the current time and the oldest packet's time.
        short seqNum = resendList.peek();
        long oldSendTime = resendTimes[seqNum];
        long timePassed = now - oldSendTime;
        
        //If the oldest packet has been in the list for a time longer than the specified timeout, resend it, updating the data and buffers as appropriate.
        if (timePassed > timeout) {
            short resentSeq = resendList.pop();
            waitSub();
            sub.send(sendBuffer[resentSeq]);
            resendTimes[seqNum] = now;
            resendList.add(resentSeq);
        }return true;
    }
    
    /**
     * If there is a message from the source waiting to be sent and the send window
     * is not full and the substrate can accept a packet, create a packet containing the message
     * and send it, after updating the send buffer and related data
     */
    private boolean sendReadyPacket(long time) {
        //If the source has no elements to send and the substrate is not ready, return.
        if(fromSrc.isEmpty() ||  !sub.ready())
            return false;
        while(!fromSrc.isEmpty()){
            
            //If the resendList has elements to send, but the packets in the receiver buffer exceed the window size, return.
            if(!resendList.isEmpty()){
                short lastUnAck = resendList.peek();
                int packetsInReceiverBuff= diff(nextSequenceNumber,lastUnAck);
                if(packetsInReceiverBuff >= wSize-1){
                    return false;
                }
            }
            //Otherwise, prepare the appropriate packet with fromSrc data, and send it.
            String message = fromSrc.poll();
            Packet out = new Packet();
            out.payload = message;
            out.seqNum = nextSequenceNumber;
            out.type = Packet.DATA_TYPE;
            waitSub();
            sub.send(out);
            
            //Update the sendBuffer, resendTimes, the resendList, and the nextSequenceNumber as necessary.
            sendBuffer[nextSequenceNumber] = out;
            resendTimes[nextSequenceNumber] = time;
            resendList.add(nextSequenceNumber);
            nextSequenceNumber = incr(nextSequenceNumber);
            //return true;
        }
        return true;
    }
    /**
     * Counts how many unacked packets we have
     * @return int number of unacked packets
     */
    private int numUnAckedPackets() {
        int numUnAckedPackets = 0;
        for (Packet p : sendBuffer) {
            if (p != null)
                numUnAckedPackets++;
        }
        return numUnAckedPackets;
    }
    
    /**Wait for the substrate to be ready.
     * Allows for the Substrate to prepare prior to sending a packet to
     * it, in order to make sure that packets are not lost while trying
     * to be sent to it.
     */
    private void waitSub(){
        while(!sub.ready()){
            try{
                Thread.sleep(1);
            }catch(Exception e){
                System.out.println("Rdt waiting for sub error" + e);
            }
        }
    }
    /** Send a message to peer.
     *  @param message is a string to be sent to the peer
     */
    public void send(String message) {
        try {
            fromSrc.put(message);
        } catch (Exception e) {
            System.err.println("Rdt:send: put exception" + e);
            System.exit(1);
        }
    }
    
    /** Test if Rdt is ready to send a message.
     *  @return true if Rdt is ready
     */
    public boolean ready() {
        return fromSrc.remainingCapacity() > 0;
    }
    
    /** Get an incoming message.
     *  @return next message
     */
    public String receive() {
        String s = null;
        try {
            s = toSnk.take();
        } catch (Exception e) {
            System.err.println("Rdt:receive: take exception" + e);
            System.exit(1);
        }
        return s;
    }
    
    /** Test for the presence of an incoming message.
     *  @return true if there is an incoming message
     */
    public boolean incoming() {
        return toSnk.size() > 0;
    }
}
