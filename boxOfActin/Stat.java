package boxOfActin;

public class Stat {
	double mean,median,stdDev;
	
	public Stat () {}
	public Stat (double mean, double stdDev) {
		this.mean = mean;
		this.stdDev = stdDev;
	}
	
	public void setMean (double mean) {
		this.mean = mean;
	}
	
	public void setMedian (double median) {
		this.median = median;
	}
	
	public void setStdDev (double stdDev) {
		this.stdDev = stdDev;
	}
	
	public double getMean () {
		return mean;
	}
	
	public double getMedian () {
		return median;
	}
	
	public double getStdDev () {
		return stdDev;
	}
	
}
