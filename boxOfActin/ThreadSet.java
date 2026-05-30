package boxOfActin;


public class ThreadSet {
	ThreadSpawn [] spawn;
	int numThreads;
	Barrier barrier;
	String commandName;
	int jobId;	// integer that defines the task to be performed by threads
	int [] jobDiv;		// delimits array ranges for each thread
	
	
	public ThreadSet (int numThreads, String commandName) {
		this.numThreads = numThreads;
		this.commandName = commandName;
		this.barrier = new Barrier (numThreads, commandName);
		spawn = new ThreadSpawn [numThreads];
		for (int i = 0; i < numThreads; i++) {
			spawn[i] = new ThreadSpawn(i, this);
			spawn[i].setDaemon(true);
			spawn[i].start();
		}
		jobDiv = new int [numThreads+1];
	}
	
	
	class ThreadSpawn extends Thread {
		ThreadSet mySet;
		int threadId;
		
		ThreadSpawn (int threadId, ThreadSet mySet) {
			this.threadId = threadId;
			this.mySet = mySet;
		}
		
		public void run() {
			Thing.tlsThreadId.get()[0] = threadId;
			mySet.done();
			while (true) {
				mySet.execute(threadId);
				mySet.done();
			}
		}
	}
	
	public void divideAndConquer (int jobId) { }
	
	public void regroup (int jobId) { }
	
	public void execute (int threadId) { }
	
	public void spawn () { barrier.startThreads(); }
	public void gather () { barrier.waitOnThreads(); }
	public void done () { barrier.threadDone(); }
}