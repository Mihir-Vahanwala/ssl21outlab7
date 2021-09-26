package bobby;

import java.net.*;
import java.io.*;
import java.util.*;

import java.util.concurrent.Semaphore;



public class ServerThread implements Runnable{
	private Board board;
	private int id;
	private boolean registered;
	private int roundsPlayed;
	private BufferedReader input;
	private PrintWriter output;
	private Socket socket;

	public ServerThread(Board board, int id, Socket socket){
		
		this.board = board;

		//id from 0 to 4 means detective, -1 means fugitive
		this.id = id;
		
		this.registered = false;

		if (id == -1){
			this.roundsPlayed = -1;
		}
		else{
			this.roundsPlayed = 0;
		}

		this.socket = socket;
	}

	public void run(){

		try{

			/*
			PART 0_________________________________
			Set the sockets up
			*/
			
			try{
				this.input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				this.output = new PrintWriter(socket.getOutputStream(), true);
				if (this.id == -1){
					output.println("Welcome. You play Fugitive. You start on square 42. Make a move, and wait for feedback");
				}
				else{
					output.println(String.format("Welcome. You play Detective %d. You start on square 0. Make a move, and wait for feedback", this.id));
				}
			}
			catch (IOException i){
				/*
				there's no use keeping this thread, so undo what the
				server did when it decided to run it
				*/
				this.board.threadInfoProtector.acquire();
				this.board.totalThreads--;
				this.board.erasePlayer(this.id);
				this.board.threadInfoProtector.release();
				return;
			}

			//__________________________________________________________________________________________

			while(true){
				boolean quit = false;
				boolean walkaway = false;
				int target = -1;

				/*
				invariant: walkaway only if quit

				walkaway means that either player wants to stop (cmd = "Q")
				or there is an exception, or
				that the game is dead without the player having ever propagated a move to the board

				in short, not walkaway means that the player deserves to see the feedback

				quit means that the thread decides that it will not play the next round

				show the player feedback iff not walkaway iff socket stuff kept open

				________________________________________________________________________________________
				
				PART 1___________________________
				read what the client has to say.
				
				totalThreads and quitThreads are relevant only to the moderator, so we can 
				edit them in the end too, just before
				enabling the moderator. (we have the quit flag at our disposal)

				For now, if the player wants to quit,
				just make the id available, by calling erasePlayer.
				this MUST be called by acquiring the threadInfoProtector! 
				
				After that, say goodbye to the client if walkaway is true

				*/

				if (this.id == -1 && this.roundsPlayed == -1){
					target = 42;
				}

				else{

					String cmd = "";
					try {
						cmd = input.readLine();
					} 
					catch (IOException i) {
						
						this.board.threadInfoProtector.acquire();
						this.board.erasePlayer(this.id);
						this.board.threadInfoProtector.release();

						// release everything socket related
						quit = true;
						walkaway = true;
						input.close();
						output.close();
						socket.close();
					}

					if (cmd.equals("Q")) {
						// client wants to disconnect, so that is that.
						this.board.threadInfoProtector.acquire();
						this.board.erasePlayer(this.id);
						this.board.threadInfoProtector.release();

						// release everything socket related
						quit = true;
						walkaway = true;
						input.close();
						output.close();
						socket.close();
					}

					else{
						try{
							target = Integer.parseInt(cmd);
						}
						catch(Exception e){
							/*
							do nothing for a mispressed key
							*/
							target = -1;
						}
					}

				}


				/*
				In the synchronization here, playingThreads is sacrosanct.
				DO NOT touch it!
				
				Note that the only thread that can write to playingThreads is
				the Moderator, and it doesn't have the permit to run until we 
				are ready to cross the second barrier.
				
				______________________________________________________________________________________
				PART 2______________________
				entering the round

				you must acquire the permit the moderator gave you to enter, 
				regardless of whether you're new
				*/
				if (!this.registered){
					this.board.registration.acquire();
					this.registered = true;
					this.board.threadInfoProtector.acquire();
					this.board.installPlayer(id);
					this.board.threadInfoProtector.release();
				}

				this.board.reentry.acquire();

				

				/*
				acquire the permit to access thread info, 
				check if the game is dead. if yes, decide to
				quit here

				if you've played earlier rounds, don't walk away,
				keep the socket open to receive feedback
				*/

				this.board.threadInfoProtector.acquire();
				if (this.board.dead){
					this.board.erasePlayer(this.id);
					this.board.threadInfoProtector.release();
					
					//set the quit flag and off the client goes
					quit = true;
					if (this.roundsPlayed == 0){
						walkaway = true;
						input.close();
						output.close();
						socket.close();
					}
					else{
						walkaway = false;
					}
					
				}
				this.board.threadInfoProtector.release();
				
				/*
				_______________________________________________________________________________________
				PART 3___________________________________
				play the move you read in PART 1 
				if you haven't decided to quit

				if the fugitive is making it's first move,
				then grab a lock and set embryo to false
				*/

				if (!quit){
					this.roundsPlayed += 1;
					this.board.threadInfoProtector.acquire();
					if (this.id == -1)   {
						if (this.roundsPlayed == 0){
							
							this.board.embryo = false;
							
						}
						this.board.moveFugitive(target);
					}

					else{
						this.board.moveDetective(this.id, target);
					}

					this.board.threadInfoProtector.release();
						
				}
				
				/*

				_______________________________________________________________________________________

				PART 4_____________________________________________
				cyclic barrier, first part
				
				execute barrier, so that we wait for all playing threads to play

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

				/*
				________________________________________________________________________________________

				PART 5_______________________________________________
				get the State of the game, and process accordingly. 

				recall that you can only do this if you're not walking away, you took that
				decision in PARTS 1 and 2

				It is here that everyone can detect if the game is over in this round, and decide to quit
				*/

				if (!walkaway && this.roundsPlayed > 0){
					String feedback;
					this.board.threadInfoProtector.acquire();
					if (this.id == -1){
						feedback = this.board.showFugitive();
					}
					else{
						feedback = this.board.showDetective(this.id);
					}
					this.board.threadInfoProtector.acquire();

					//pass this to the client via the socket output
					try{
						output.println(feedback);
					}
					//in case of IO Exception, we just drop the whole idea
					catch(Exception i){
						quit = true;
						walkaway = true;
						this.board.threadInfoProtector.acquire();
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
						this.board.erasePlayer(this.id);
						this.board.threadInfoProtector.release();

						// release everything socket related
						walkaway = true;
						input.close();
						output.close();
						socket.close();
					}
				}

				/*
				__________________________________________________________________________________
				PART 6A____________________________
				wrapping up


				everything that could make a thread quit has happened
				now, look at the quit flag, and, if true, make changes in
				totalThreads and quitThreads
				*/

				if (quit){
					this.board.threadInfoProtector.acquire();
					this.board.totalThreads --;
					this.board.quitThreads++;
					this.board.threadInfoProtector.release();
				}

				/*
				__________________________________________________________________________________
				PART 6B______________________________
				second part of the cyclic barrier
				that makes it reusable
				
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

				/*
				__________________________________________________________________________________
				PART 6C_________________________________
				actually finishing off a thread
				that decided to quit
				*/
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