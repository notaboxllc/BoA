package boxOfActin;

import java.text.*;

public class RunTimer {
	long time, startTime, stopTime;
	String commandName, timeString;
	DecimalFormat timeOutFormat = new DecimalFormat ("#00.00#; -#00.00#");
	
	public RunTimer (String commandName) {
		time = 0;
		this.commandName = commandName;
	}
	
	public void start () {
		startTime = java.lang.System.currentTimeMillis();
	}
	
	public void stop () {
		stopTime = java.lang.System.currentTimeMillis();
	}
	
	public void inc () {
		time += stopTime-startTime;
	}
	
	public void stopInc () {
		stop();
		inc();
	}
	
	public void reportTime () {
		timeString = String.format("%2f",time/1000.0); //timeOutFormat.format(time/1000));
		talkln (commandName + " took " + timeString + " seconds.");
	}
	
	public double getTimeSum() {
		return (time/1000);		// return time sum in seconds
	}
	
	public void resetTime () {
		time = 0;
	}
	
	private void talkln (String info) {
		System.out.println(info);
	}
}