import java.net.*;
import java.io.*;
import java.util.*;

public class Receiver {
  public static void main(String[] args) throws Exception {
    // format is java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>
    // RN controls how frequently ACK packets are intentionally dropped for testing reliability
    if (args.length != 5) {
      System.err.println("the format is java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
      System.exit(1); // unsuccessful termination
    }

    String senderIpStr = args[0];
    int senderAckPort;
    int rcvDataPort;
    String outputFile = args[3];
    int rn;

    try {
      senderAckPort = Integer.parseInt(args[1]);
      rcvDataPort = Integer.parseInt(args[2]);
      rn = Integer.parseInt(args[4]);
    } catch (NumberFormatException e) {
      System.err.println("Bad numeric argument: " + e.getMessage());
      System.exit(1);
      return;
    }

    InetAddress senderIp = InetAddress.getByName(senderIpStr);
    DatagramSocket socket = new DatagramSocket(rcvDataPort);

    // Receiver-side GBN buffering (cumulative ACKs).
    // Note: Receiver CLI does not provide N; we buffer conservatively up to full sequence space.
    // The receiver only delivers bytes to the file once all earlier sequence numbers have arrived
    final int windowSize = 128;

    Map<Integer, byte[]> buffer = new HashMap<>();
    int expectedSeq = 1;
    int lastCumulativeAck = 0;

    int ackCount = 0; // intended ACKs, 1-indexed for ChaosEngine.shouldDrop

    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
      // -------------------------
      // Phase 1: Handshake (SOT)
      // -------------------------
      // Waits for the sender's SOT control packet and replies with a single ACK seq=0
      while (true) {
        DSPacket pkt = receivePacket(socket);
        if (pkt.getType() == DSPacket.TYPE_SOT && pkt.getSeqNum() == 0) {
          System.out.println("Received SOT seq=0");
          break;
        }
      }

      // ACK SOT (Seq=0)
      ackCount++;
      sendAckWithChaos(socket, senderIp, senderAckPort, 0, ackCount, rn);
      System.out.println("Handshake success");

      // -------------------------
      // Phase 2: Data Transfer
      // -------------------------
      // Accepts DATA packets, buffers out-of-order ones, and sends cumulative ACKs for the last in-order seq
      while (true) {
        DSPacket pkt = receivePacket(socket);
        int type = pkt.getType();
        int seq = pkt.getSeqNum();

        if (type == DSPacket.TYPE_DATA) {
          System.out.println("Received DATA seq=" + seq);

          int distAhead = modDistance(expectedSeq, seq);
          boolean inWindow = distAhead < windowSize;

          if (inWindow) {
            if (!buffer.containsKey(seq)) {
              buffer.put(seq, pkt.getPayload());
            }

            while (buffer.containsKey(expectedSeq)) {
              byte[] payload = buffer.remove(expectedSeq);
              fos.write(payload);
              lastCumulativeAck = expectedSeq;
              expectedSeq = (expectedSeq + 1) % 128;
            }

            ackCount++;
            sendAckWithChaos(socket, senderIp, senderAckPort, lastCumulativeAck, ackCount, rn);
          } else {
            // Out of window: discard and re-send last cumulative ACK.
            ackCount++;
            sendAckWithChaos(socket, senderIp, senderAckPort, lastCumulativeAck, ackCount, rn);
          }
        } else if (type == DSPacket.TYPE_EOT) {
          // Phase 3 teardown: sender is finished sending file data and is closing the session
          System.out.println("Received EOT seq=" + seq);

          // Acknowledge the EOT so the sender can stop retransmitting and record completion time
          ackCount++;
          sendAckWithChaos(socket, senderIp, senderAckPort, seq, ackCount, rn);

          System.out.println("Teardown success");
          break;
        } else if (type == DSPacket.TYPE_SOT) {
          // Duplicate SOT: re-ACK it (subject to chaos).
          System.out.println("Received duplicate SOT seq=" + seq);
          ackCount++;
          sendAckWithChaos(socket, senderIp, senderAckPort, 0, ackCount, rn);
        }
      }
    } finally {
      socket.close();
    }
  }

  private static DSPacket receivePacket(DatagramSocket socket) throws IOException {
    byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
    DatagramPacket dp = new DatagramPacket(buf, buf.length);
    socket.receive(dp);
    return new DSPacket(dp.getData());
  }

  private static void sendAckWithChaos(
      DatagramSocket socket,
      InetAddress senderIp,
      int senderAckPort,
      int ackSeq,
      int ackCount,
      int rn
  ) throws IOException {
    DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, ackSeq, null);
    if (ChaosEngine.shouldDrop(ackCount, rn)) {
      System.out.println("Dropped ACK seq=" + ackSeq);
      return;
    }

    byte[] raw = ack.toBytes();
    DatagramPacket dp = new DatagramPacket(raw, raw.length, senderIp, senderAckPort);
    socket.send(dp);
    System.out.println("Sent ACK seq=" + ackSeq);
  }

  private static int modDistance(int fromSeq, int toSeq) {
    return (toSeq - fromSeq + 128) % 128;
  }
}
