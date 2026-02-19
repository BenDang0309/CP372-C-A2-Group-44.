import java.net.*;
import java.io.*;
import java.util.*;

public class Receiver {
  public static void main(String[] args) throws Exception {
    // format is java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>
    if (args.length != 5) {
      System.err.println("the format is java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
      System.exit(1); // unsuccessful termination
    }

    try {
      String sender_ip = args[0];
      int sender_ack_port = Integer.parseInt(args[1]);
      int rcv_data_port = Integer.parseInt(args[2]);
      String output_file = args[3];
      int RN = Integer.parseInt(args[4]); // reliability number (every <RN> ACK is dropped)
      
    } catch (NumberFormatException e) {
        System.err.println("Bad numeric argument: " + e.getMessage());
    } catch (IOException ioe) {
        System.err.println(ioe.getMessage());
    }
  }
}
