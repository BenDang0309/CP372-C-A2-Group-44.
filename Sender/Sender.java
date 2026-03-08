import java.net.*;
import java.io.*;
import java.util.*;

public class Sender {
  public static void main(String[] args) throws Exception {
    // java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> <window_size>
    // window_size controls how many DATA packets can be unacknowledged at once
    if (args.length != 5 && args.length != 6) {
      System.err.println("the format is java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> <window_size> with an optional window size.");
      System.exit(1);
    }

    String rcvIpStr = args[0];
    int rcvDataPort;
    int senderAckPort;
    String inputFile;
    int timeoutMs;
    int windowSize;

    // only exists to prevent a window size input of 1 from the user (must be multiples of 4, according to specifications) 
    // while simultaneously allowing stop-and-wait to give windowSize a value of 1
    boolean SaW = false;

    try {
      rcvDataPort = Integer.parseInt(args[1]);
      senderAckPort = Integer.parseInt(args[2]);
      inputFile = args[3];
      timeoutMs = Integer.parseInt(args[4]);
      if (args.length == 6) {
        // go-back-n
        windowSize = Integer.parseInt(args[5]);
      } else {
        // stop-and-wait (if window size is 1, then its functionally identical to go-back-n)
        windowSize = 1;
        SaW = true;
      }
    } catch (NumberFormatException e) {
      System.err.println("Bad numeric argument: " + e.getMessage());
      System.exit(1);
      return;
    }

    if (windowSize <= 0 || windowSize > 128 || (!SaW && windowSize % 4 != 0)) {
      System.err.println("Invalid window size. Must be a multiple of 4 and <= 128.");
      System.exit(1);
    }

    InetAddress rcvIp = InetAddress.getByName(rcvIpStr);

    List<DSPacket> dataPackets = buildDataPackets(inputFile);
    int numData = dataPackets.size();
    int lastDataSeq = (numData == 0) ? 0 : dataPackets.get(numData - 1).getSeqNum();
    int eotSeq = (numData == 0) ? 1 : ((lastDataSeq + 1) % 128);

    DatagramSocket socket = new DatagramSocket(senderAckPort);
    socket.setSoTimeout(timeoutMs);

    long startNs = System.nanoTime();

    // -------------------------
    // Phase 1: Handshake (SOT)
    // -------------------------
    sendControl(socket, rcvIp, rcvDataPort, new DSPacket(DSPacket.TYPE_SOT, 0, null), "SOT");
    System.out.println("Sent SOT seq=0");

    int sotTimeouts = 0;
    while (true) {
      try {
        DSPacket ack = receivePacket(socket);
        if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
          System.out.println("Received ACK seq=0");
          System.out.println("Handshake success");
          break;
        }
      } catch (SocketTimeoutException ste) {
        sotTimeouts++;
        if (sotTimeouts >= 3) {
          System.out.println("Unable to transfer file.");
          socket.close();
          return;
        }
        System.out.println("Timeout waiting for SOT ACK, retransmitting SOT seq=0");
        sendControl(socket, rcvIp, rcvDataPort, new DSPacket(DSPacket.TYPE_SOT, 0, null), "SOT");
      }
    }

    // -------------------------
    // Empty file case: EOT seq=1
    // -------------------------

    // If there is no data to send, immediately send an EOT control packet and finish
    if (numData == 0) {
      if (!sendEotAndAwaitAck(socket, rcvIp, rcvDataPort, eotSeq, timeoutMs)) {
        socket.close();
        return;
      }

      double seconds = (System.nanoTime() - startNs) / 1_000_000_000.0;
      System.out.printf("Total Transmission Time: %.2f seconds%n", seconds);
      socket.close();
      return;
    }

    // -------------------------
    // Phase 2: Data Transfer 
    // -------------------------
      
    int baseIndex = 0; // oldest unACKed data packet index
    int nextIndex = 0; // next data packet index to send

    int timeoutsForSameBase = 0;
    int lastBaseIndex = baseIndex;

    while (baseIndex < numData) {
      // Fill window with new transmissions.
      // New DATA packets are sent in groups of four and permuted by the ChaosEngine to force reordering
      while (nextIndex < numData && (nextIndex - baseIndex) < windowSize) {
        int remainingWindow = windowSize - (nextIndex - baseIndex); // just in case ACK pushes index into a value that isn't a multiple of 4
        int groupSize = Math.min(4, Math.max(0, remainingWindow));
        int endIndex = Math.min(nextIndex + groupSize, numData);
        List<DSPacket> group = new ArrayList<>(endIndex - nextIndex);
        for (int i = nextIndex; i < endIndex; i++) {
          group.add(dataPackets.get(i));
        }

        List<DSPacket> toSend = ChaosEngine.permutePackets(group);
        for (DSPacket p : toSend) {
          sendData(socket, rcvIp, rcvDataPort, p, false);
        }

        nextIndex = endIndex;
      }

      try {
        DSPacket ack = receivePacket(socket);
        if (ack.getType() != DSPacket.TYPE_ACK) {
          continue;
        }

        int ackSeq = ack.getSeqNum();
        System.out.println("Received ACK seq=" + ackSeq);

        // Cumulative ACK: move baseIndex forward if ACK is within outstanding range.
        int baseSeq = dataPackets.get(baseIndex).getSeqNum();
        int outstanding = nextIndex - baseIndex;

        int dist = modDistance(baseSeq, ackSeq);
        if (dist < outstanding) {
          baseIndex += (dist + 1);
          timeoutsForSameBase = 0;
          lastBaseIndex = baseIndex;
        } else {
          timeoutsForSameBase = 0;
          System.out.println("Received ACK seq=" + ackSeq + " (no advance), retransmitting window from base seq=" + baseSeq);
          retransmitWindow(socket, rcvIp, rcvDataPort, dataPackets, baseIndex, nextIndex);
        }
      } catch (SocketTimeoutException ste) {
        if (baseIndex == lastBaseIndex) {
          timeoutsForSameBase++;
        } else {
          lastBaseIndex = baseIndex;
          timeoutsForSameBase = 1;
        }

        if (timeoutsForSameBase >= 3) {
          System.out.println("Unable to transfer file.");
          socket.close();
          return;
        }

        System.out.println("Timeout, retransmitting window from base seq=" + dataPackets.get(baseIndex).getSeqNum());
        retransmitWindow(socket, rcvIp, rcvDataPort, dataPackets, baseIndex, nextIndex);
      }
    }

    // -------------------------
    // Phase 3: Teardown (EOT)
    // -------------------------
    // Signal to the receiver that no more DATA packets will be sent and wait  
    // for a final ACK on the EOT sequence number before declaring success.
    if (!sendEotAndAwaitAck(socket, rcvIp, rcvDataPort, eotSeq, timeoutMs)) {
      socket.close();
      return;
    }

    // At this point the receiver has ACKed EOT, so the transfer is complete and
    // the total application-level transmission time can be reported.
    double seconds = (System.nanoTime() - startNs) / 1_000_000_000.0;
    System.out.printf("Total Transmission Time: %.2f seconds%n", seconds);

    socket.close();
  }

  // Build a list of DATA packets from the input file, assigning sequence
  // numbers starting at 1 and incrementing modulo 128 for each payload chunk.
  private static List<DSPacket> buildDataPackets(String inputFile) throws IOException {
    List<DSPacket> packets = new ArrayList<>();

    File f = new File(inputFile);
    if (!f.exists()) {
      throw new FileNotFoundException("Input file not found: " + inputFile);
    }

    // Read the entire file using a fixed-size buffer matching the maximum
    // protocol payload so every DATA packet respects the 124-byte limit.
    try (FileInputStream fis = new FileInputStream(f)) {
      byte[] buf = new byte[DSPacket.MAX_PAYLOAD_SIZE];
      int read;
      int seq = 1;
      while ((read = fis.read(buf)) != -1) {
        if (read == 0) {
          continue;
        }
        // Copy exactly the bytes read into a right-sized payload array for this DATA packet.
        byte[] payload = Arrays.copyOf(buf, read);
        packets.add(new DSPacket(DSPacket.TYPE_DATA, seq, payload));
        seq = (seq + 1) % 128;
      }
    }

    return packets;
  }

  // Helper for sending SOT/EOT control packets to the receiver using the UDP socket.
  private static void sendControl(DatagramSocket socket, InetAddress ip, int port, DSPacket pkt, String label)
      throws IOException {
    byte[] raw = pkt.toBytes();
    DatagramPacket dp = new DatagramPacket(raw, raw.length, ip, port);
    socket.send(dp);
  }

  // Send a DATA packet and log whether it is an original transmission or a retransmission.
  private static void sendData(DatagramSocket socket, InetAddress ip, int port, DSPacket pkt, boolean retransmit)
      throws IOException {
    byte[] raw = pkt.toBytes();
    DatagramPacket dp = new DatagramPacket(raw, raw.length, ip, port);
    socket.send(dp);
    if (retransmit) {
      System.out.println("Retransmitted DATA seq=" + pkt.getSeqNum());
    } else {
      System.out.println("Sent DATA seq=" + pkt.getSeqNum());
    }
  }


  // Block until the next 128-byte UDP datagram arrives, then parse it into a DSPacket.
  private static DSPacket receivePacket(DatagramSocket socket) throws IOException {
    byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
    DatagramPacket dp = new DatagramPacket(buf, buf.length);
    socket.receive(dp);
    return new DSPacket(dp.getData());
  }

  // Retransmit all outstanding DATA packets in the current window, starting
  // from baseIndex up to (but not including) nextIndex, again using the
  // ChaosEngine permutation for each group of four.
  private static void retransmitWindow( DatagramSocket socket, InetAddress ip, 
  int port, List<DSPacket> dataPackets, int baseIndex, int nextIndex
  ) throws IOException {
    int i = baseIndex;
    while (i < nextIndex) {
      int endIndex = Math.min(i + 4, nextIndex);
      List<DSPacket> group = new ArrayList<>(endIndex - i);
      for (int j = i; j < endIndex; j++) {
        group.add(dataPackets.get(j));
      }

      List<DSPacket> toSend = ChaosEngine.permutePackets(group);
      for (DSPacket p : toSend) {
        sendData(socket, ip, port, p, true);
      }

      i = endIndex;
    }
  }

  // Send a single EOT control packet and wait for its matching ACK, retrying
  // on timeout up to three times before giving up and signalling failure.
  private static boolean sendEotAndAwaitAck(DatagramSocket socket, InetAddress ip, int port, int eotSeq, int timeoutMs)
      throws IOException {
    DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);

    sendControl(socket, ip, port, eot, "EOT");
    System.out.println("Sent EOT seq=" + eotSeq);

    int timeouts = 0;
    while (true) {
      try {
        DSPacket ack = receivePacket(socket);
        if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
          System.out.println("Received ACK seq=" + eotSeq);
          System.out.println("Teardown success");
          return true;
        }
      } catch (SocketTimeoutException ste) {
        timeouts++;
        if (timeouts >= 3) {
          System.out.println("Unable to transfer file.");
          return false;
        }
        System.out.println("Timeout waiting for EOT ACK, retransmitting EOT seq=" + eotSeq);
        sendControl(socket, ip, port, eot, "EOT");
      }
    }
  }

  // Compute the forward distance from one sequence number to another in the
  // modulo-128 space used by the protocol for all wrap-around comparisons.
  private static int modDistance(int fromSeq, int toSeq) {
    return (toSeq - fromSeq + 128) % 128;
  }
}

  
