package bobby;

import java.net.*;
import java.io.*;
import java.util.*;

import java.util.concurrent.Semaphore;


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class ScotlandYard implements Runnable{

	/*
		this is a wrapper class for the game.
		It just loops, and runs game after game
	*/

	public int port;

	public ScotlandYard(int port){
		this.port = port;
	}

	public void run(){
		while (true){
			Thread tau = new Thread(new ScotlandYardGame(this.port));
			tau.start();
			try{
				tau.join();
			}
			catch (InterruptedException e){
				return;
			}
		}
	}

	public class ScotlandYardGame implements Runnable{
		private Board board;
		private ServerSocket server;
		public int port;
		private ExecutorService threadPool;

		public ScotlandYardGame(int port){
			this.port = port;
			this.board = new Board();
			try{
				this.server = new ServerSocket(port);
				System.out.println(String.format("Game on, join Port %d", port));
				server.setSoTimeout(5000);
			}
			catch (IOException i) {
				return;
			}
			this.threadPool = Executors.newFixedThreadPool(10);
		}


		public void run(){

			try{
			
				//INITIALISATION: get the game going

				

				Socket socket;
				
				
				/*
				listen for two clients: one to play fugitive, one to play Detective 0
				only then can we begin a game
				
				here, it is actually ok to edit this.board.dead, because the game hasn't begun
				*/
				while (true){
					try {
						socket = this.server.accept();
					} 
					catch (SocketTimeoutException t) {
						continue;
					}
					
					this.board.threadInfoProtector.acquire();
					if (this.board.dead){
						this.threadPool.execute(new ServerThread(this.board, -1, socket));
						this.board.totalThreads += 1;
						this.board.dead = false;
						this.board.threadInfoProtector.release();
						socket = null;
						continue;
					}
					
					else {
						int zero = this.board.getAvailableID();
						this.threadPool.execute(new ServerThread(this.board, zero, socket));
						this.board.totalThreads += 1;
						this.board.threadInfoProtector.release();
						socket = null;
						break;
					}

				}
				
				
				//Ready to start playing! spawn the moderator thread
				Thread mod = new Thread(new Moderator(board));
				mod.start();
				
				
				
				while (true){



					/*
					listen on the server, accept connections
					if there is a timeout, check that the game is still going on, and then listen again!
					*/

					try {
						socket = this.server.accept();
					} 
					catch (SocketTimeoutException t){
						this.board.threadInfoProtector.acquire();
						if (this.board.dead) {
							this.board.threadInfoProtector.release();
							break;
						}
						this.board.threadInfoProtector.release();
						continue;
					}
					
					
					/*
					acquire thread info lock, and decide whether you can serve the connection at this moment,

					if you can't, drop connection (game full, game dead), continue, or break.

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

				/*
				reap the moderator thread, close the server, 
				
				kill threadPool (Careless Whispers BGM stops)
				*/
				mod.join();
				this.server.close();
				this.threadPool.shutdown();
				System.out.println(String.format("Game Over, Port %d", port));
				return;
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

		
	}

	public static void main(String[] args) {
		for (int i=0; i<args.length; i++){
			int port = Integer.parseInt(args[i]);
			Thread tau = new Thread(new ScotlandYard(port));
			tau.start();
		}
	}
}