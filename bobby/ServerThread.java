package bobby;

import java.net.*;
import java.io.*;
import java.util.*;

import java.util.concurrent.Semaphore;



public class ServerThread implements Runnable{
	private Board board;
	private int id;
	private boolean registered;
	private BufferedReader input;
	private PrintWriter output;
	private Socket socket;

	public ServerThread(Board board, int id, Socket socket){
		
		this.board = board;

		//id from 0 to 4 means detective, -1 means fugitive
		this.id = id;
		
		this.registered = false;

		this.socket = socket;
	}

	public void run(){

		try{
			
			try{
				this.input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				this.output = new PrintWriter(socket.getOutputStream(), true);
				if (this.id == -1){
					output.println("Welcome. You play Fugitive. You start on square 0. Make a move, and wait for feedback");
				}
				else{
					output.println(String.format("Welcome. You play Detective %d. You start on square 0. Make a move, and wait for feedback", this.id));
				}
			}
			catch (IOException i){
				this.board.threadInfoProtector.acquire();
				this.board.totalThreads--;
				this.board.erasePlayer(this.id);
				this.board.threadInfoProtector.release();
				return;
			}

			while(true){
				/*
				In the synchronization here, playingThreads is sacrosanct.
				DO NOT touch it!
				(acquiring the lock to modify it is inefficient)
				
				Note that the only thread that can write to playingThreads is
				the Moderator, and it doesn't have the permit to run until we 
				are ready to cross the second barrier.
				*/


				//you must acquire the permit the moderator gave you to enter, 
				//regardless of whether you're new
				if (!this.registered){
					this.board.registration.acquire();
					this.registered = true;
					this.board.installPlayer(id);
				}

				this.board.reentry.acquire();

				boolean quit = false;

				/*
				If someone wants to exit in this round, erase them from the map, make their
				ID available, increment quitThreads, decrement totalThreads
				
				close the socket, but let the thread play along for syncing up
				
				
				First, acquire the permit to access thread info, 
				check if the game is dead. if yes,
				exit here
				*/

				this.board.threadInfoProtector.acquire();
				if (this.board.dead){
					quit = true;
					this.board.quitThreads ++;
					this.board.totalThreads -- ;
					this.board.erasePlayer(this.id);
				}
				this.board.threadInfoProtector.release();
				
				if (quit){
					//release everything socket related
					input.close();
					output.close();
					socket.close();
				}
				
				/*
				look at the socket buffer and play. 
				*/

				if (!quit){
					String cmd = "";
					//read the input from socket input
					try{
						cmd =  input.readLine();
					}
					catch(IOException i){
						quit = true;
						this.board.threadInfoProtector.acquire();
						this.board.quitThreads++;
						this.board.totalThreads--;
						this.board.erasePlayer(this.id);
						this.board.threadInfoProtector.release();

						// release everything socket related
						input.close();
						output.close();
						socket.close();
					}


					if (cmd.equals("Q")){
						//client wants to disconnect, so that is that.
						quit = true;
						this.board.threadInfoProtector.acquire();
						this.board.quitThreads++;
						this.board.totalThreads--;
						this.board.erasePlayer(this.id);
						this.board.threadInfoProtector.release();

						//release everything socket related
						input.close();
						output.close();
						socket.close();
					}

					else{
						//client specified a target, get from what you read
						try{
							int target = Integer.parseInt(cmd);
							
							if (this.id == -1){
								this.board.moveFugitive(target);
							}

							else{
								this.board.moveDetective(this.id, target);
							}
						}	
						catch (Exception e){
							quit = true;
							this.board.threadInfoProtector.acquire();
							this.board.quitThreads++;
							this.board.totalThreads--;
							this.board.erasePlayer(this.id);
							this.board.threadInfoProtector.release();

							// release everything socket related
							input.close();
							output.close();
							socket.close();
						}
					}
				}
				
				/*execute barrier, so that we wait for all playing threads to play

				Hint: use the count to keep track of how many threads hit this barrier
				they must acquire a permit to cross. The last thread to hit the barrier can 
				release permits for them all.
				*/
				this.board.countProtector.acquire();
				this.board.count++;
				if (this.board.count == this.board.playingThreads){
					this.board.barrier1.release(this.board.playingThreads);
				}
				this.board.countProtector.release();
				this.board.barrier1.acquire();

				/*get the State of the game, and process accordingly. 

				It is here that everyone can detect that game is over, and decide to quit
				*/

				if (!quit){
					String feedback;
					if (this.id == -1){
						feedback = this.board.showFugitive();
					}
					else{
						feedback = this.board.showDetective(this.id);
					}

					//pass this to the client via the socket output
					try{
						output.println(feedback);
					}
					//in case of IO Exception, we just drop the whole idea
					catch(Exception i){
						quit = true;
						this.board.threadInfoProtector.acquire();
						this.board.quitThreads++;
						this.board.totalThreads--;
						this.board.erasePlayer(this.id);
						this.board.threadInfoProtector.release();

						// release everything socket related
						input.close();
						output.close();
						socket.close();
					}

					
					
					//parse this feedback to find if game is on
					String indicator;

					indicator = feedback.split("; ")[2];

					if (!indicator.equals("Play")){
						quit = true;
						this.board.threadInfoProtector.acquire();
						this.board.quitThreads++;
						this.board.totalThreads--;
						this.board.erasePlayer(this.id);
						this.board.threadInfoProtector.release();

						// release everything socket related
						input.close();
						output.close();
						socket.close();
					}
				}

				/*
				our threads must wait together before proceeding to the next round

				Reuse count to keep track of how many threads hit this barrier2 

				The code is similar. However, the last thread to hit this barrier must also 
				permit the moderator to run
				*/
				this.board.countProtector.acquire();
				this.board.count--;
				if (this.board.count == 0) {
					this.board.barrier2.release(this.board.playingThreads);
					this.board.moderatorEnabler.release();
				}
				this.board.countProtector.release();
				this.board.barrier2.acquire();

				//finally, it is safe to actually destroy threads
				if (quit){
					return;
				}
			}
		}
		catch (InterruptedException ex) {
			return;
		}
		catch (IOException i){
			return;
		}
	}

	
}