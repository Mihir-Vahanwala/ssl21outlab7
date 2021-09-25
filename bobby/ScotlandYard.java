package bobby;

import java.net.*;
import java.io.*;
import java.util.*;

import java.util.concurrent.Semaphore;


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class ScotlandYard implements Runnable{
	private Board board;
	private ServerSocket server;
	public int port;
	private ExecutorService threadPool;

	public ScotlandYard(int port){
		this.port = port;
		this.board = new Board();
		try{
			this.server = new ServerSocket(port);
		}
		catch (IOException i) {
			return;
		}
		this.threadPool = Executors.newFixedThreadPool(10);
	}


	public void run(){

		try{
		
			//INITIALISATION: get the game going

			//listen for a fugitive and spawn a thread
			Socket socket;
			try {
				socket = this.server.accept();
			} 
			catch (IOException e) {
				return;
			}
			this.board.threadInfoProtector.acquire();
			this.threadPool.execute(new ServerThread(this.board, -1, socket));
			this.board.totalThreads += 1;
			this.board.threadInfoProtector.release();

			socket = null;
			
			
			
			//listen for a detective and spawn a thread
			try {
				socket = this.server.accept();
			} catch (IOException e) {
				return;
			}
			this.board.threadInfoProtector.acquire();
			this.threadPool.execute(new ServerThread(this.board, 0, socket));
			this.board.totalThreads += 1;
			this.board.availableIDs[0] = false;
			this.board.threadInfoProtector.release();

			socket = null;

			
			
			
			//spawn the moderator thread
			Thread mod = new Thread(new Moderator(board));
			mod.start();
			
			int excount = 0;
			
			
			while (true){
				//listen on the server, accept connections

				try {
					socket = this.server.accept();
				} catch (IOException e) {
					excount++;
					if (excount > 10){
						return;
					}
				}
				
				
				/*
				acquire thread info lock, and decide whether you can serve

				if you can't (game full, game dead), drop the connection.
				In particular, if the game is full, break out of the loop

				if you can, spawn a thread, assign an ID, increment the totalThreads

				don't forget to release lock when done!
				*/
				this.board.threadInfoProtector.acquire();
				if (this.board.dead){
					socket.close();
					this.board.threadInfoProtector.release();
					break;
				}
				int potential = this.board.getAvailableID();
				if (potential == -1){
					socket.close();
					this.board.threadInfoProtector.release();
					continue;
				}
				
				this.threadPool.execute(new ServerThread(this.board, potential, socket));
				this.board.totalThreads += 1;

				this.board.threadInfoProtector.release();

			}

			//reap the moderator thread
			mod.join();
		}
		catch (InterruptedException ex){
			System.err.println("An InterruptedException was caught: " + ex.getMessage());
			ex.printStackTrace();
			return;
		}
		catch (IOException i){
			return;
		}
	}

	public static void main(String[] args) {
		int port = Integer.parseInt(args[0]);
		ScotlandYard scot = new ScotlandYard(port);
		Thread tau = new Thread(scot);
		tau.start();
		try{
		
		tau.join();
		}
		catch(InterruptedException ex){
			return;
		}
	}
}