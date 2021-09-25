package bobby;

import java.net.*;
import java.io.*;
import java.util.*;

import java.util.concurrent.Semaphore;

public class Player{
	public static void main(String [] args){
		BufferedReader input;
		PrintWriter output;
		Socket socket;
		Console console = System.console();
		try{
			socket = new Socket("127.0.0.1", 5000);
			String feedback;
			input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			output = new PrintWriter(socket.getOutputStream(), true);
			while (true){
				while ((feedback = input.readLine()) != null){
					System.out.println(feedback);
					String move = console.readLine();
					output.println(move);
				}
			}
		}
		catch(IOException i){
			return;
		}
		
	}
}