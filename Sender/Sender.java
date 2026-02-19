import java.net.*;
import java.io.*;
import java.util.*;

public class Sender {
  public static void main(String[] args) throws Exception {
    // format is java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]
    // if has window size, GO-BACK-N. If no window size, STOP-N-WAIT
    if (args.length != 5 && args.length != 6) {
      System.err.println("the format is java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
      System.exit(1); // unsuccessful termination
    }

    try {
      String icv_ip = args[0];
      int rcv_data_port = Integer.parseInt(args[1]);
      int sender_ack_port = Integer.parseInt(args[2]);
      String input_file = args[3];
      int timeout = Integer.parseInt(args[4]); // in milliseconds
  
      int window_size = 1; // default for STOP-N-WAIT
      // if its GO-BACK-N (6 arguments provided)
      if (args.length == 6) {
        window_size = Integer.parseInt(args[5]);
      }
    } catch (NumberFormatException e) {
        System.err.println("Bad numeric argument: " + e.getMessage());
    } catch (IOException ioe) {
        System.err.println(ioe.getMessage());
    }
  }
}

