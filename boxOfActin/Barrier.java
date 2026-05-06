package boxOfActin;


public class Barrier {
	Object syncO = new Object();
	RunTimer timer;
	int numThreads;
	int numThreadsIn = 0;
	int numThreadsOut = 0;
	boolean jobDone = false;
			
	public Barrier (int numThreads, String commandName) {
		this.timer = new RunTimer (commandName);
		this.numThreads = numThreads;
	}
		
	public void startThreads() {
		synchronized(syncO) {
			timer.start(); 
			numThreadsIn = 0;
			numThreadsOut = 0;
			jobDone = false;
			syncO.notifyAll();
			}
	}
		
	public void waitOnThreads() {
		synchronized(syncO) {
			while (!jobDone) {
				try { syncO.wait(); } catch(Exception e) { talk (" Exception waiting in waitOnThreads "); }
			}
			timer.inc();
		}
	}		
		
	public void threadDone() {
		synchronized(syncO) {
			numThreadsIn += 1;
			while (numThreadsIn < numThreads) {
				try { syncO.wait(); } catch(Exception e) { talk (" Exception waiting in threadDone() "); }
			}
			numThreadsOut += 1;
			if (numThreadsOut == numThreads) { // once the last thread in (first out) comes through... job done
				timer.stop(); 
				jobDone = true;  
			}
			syncO.notifyAll();
			while (numThreadsOut > 0) {
				try { syncO.wait(); } catch(Exception e) { talk (" Exception waiting in threadDone() "); }
			}
		}
	}
	
	private void talk (String info) {
		System.out.println(info);
	}
}