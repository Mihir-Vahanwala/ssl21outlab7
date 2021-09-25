package bobby;

import java.net.*;
import java.io.*;
import java.util.*;

import java.util.concurrent.Semaphore;

public class Moderator implements Runnable{
	private Board board;
	
	public Moderator(Board board){
		this.board = board;
	}

	public void run(){
		while (true){
			try{
				/*acquire permits: 
				
				1) the moderator itself needs a permit to run, see Board
				2) one needs a permit to modify thread info

				*/
				this.board.moderatorEnabler.acquire();
				this.board.threadInfoProtector.acquire();

				/* 
				look at the thread info, and decide how many threads can be 
				permitted to play next round

				playingThreads: how many began last round
				quitThreads: how many quit in the last round
				totalThreads: how many are ready to play next round
				*/
				
				
				//find out how many newbies
				int newbies = this.board.totalThreads - this.board.playingThreads + this.board.quitThreads;

	

				/*
				If ALL threads quit last round, it means game over
				We can set dead to true
				*/

				if (this.board.playingThreads == this.board.quitThreads && this.board.time != 0){
					this.board.dead = true;
				}


				/*
				If there ARE no threads, it means Game Over, and there are no 
				more new threads to "reap". In particular, we can set dead to true, then 
				the server won't spawn any more threads when it gets the lock.

				Thus, the moderator's job will be done, and this thread can terminate.
				As good practice, we will release the "lock" we held. 
				*/

				if (this.board.totalThreads == 0){
					this.board.dead = true;
					this.board.threadInfoProtector.release();
					return;
				}
				
				/* 
				If we have come so far, the game is afoot.

				totalThreads is accurate. 
				Correct playingThreads
				reset quitThreads


				Release permits for threads to play, and the permit to modify thread info
				*/

				this.board.playingThreads = this.board.totalThreads;
				this.board.quitThreads = 0;
				
				this.board.registration.release(newbies);
				this.board.reentry.release(this.board.playingThreads);
				this.board.threadInfoProtector.release();
			}
			catch (InterruptedException ex){
				System.err.println("An InterruptedException was caught: " + ex.getMessage());
				ex.printStackTrace();
				return;
			}
		}
	}
}