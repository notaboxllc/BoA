package boxOfActin;

import javax.swing.*;
import java.io.*;

public class HistogramPlus2 {
	static HistogramPlus2 [] theHistos = new HistogramPlus2[50];
	static int histCt = 0;
	JFrame parent;
	int binCt, ptCt, passCt;
	double startVal,stopVal,stepVal;
	double minVal,maxVal,maxMagVal;
	double [] binIntervals;
	double [] binSums;
	String [] infoS = new String[1];
	String intervalTitle = "intervals";
	String unitsString = "";
	String path;
	String name;
	boolean sumInfoCurrent = false;
	boolean tossTail = false;
	boolean tossHead = false;
	boolean reportSum = true;
	boolean reportNorm = false;
	boolean reportAve = false;
	boolean reportScaled = false;
	
	// for file writing
	static String sepString = ";";
	boolean fileInitialized = false;
	File histoFile;
	FileWriter histoFW;
	PrintWriter histoPW;
	
	static double scaledNormMag = 1.0;		// mag of range in which to scale histograms
	
	public HistogramPlus2 (int binCt, double startVal, double stopVal, String folderPath, String name, JFrame parent) {
		this.parent = parent;
		this.binCt = binCt;
		this.startVal = startVal;
		this.stopVal = stopVal;
		this.path = folderPath;
		this.name = name;
		infoS[0] = " ";	//dummy
		binSums = new double[binCt];
		binIntervals = new double[binCt];
		stepVal = (stopVal-startVal)/binCt;
		initialize();
		addHist(this);
	}
	
	private void initialize () {
		ptCt = 0;
		passCt = 0;
		for (int i=0;i<binCt;i++) {
			binIntervals[i] = i*stepVal + startVal;
			binSums[i] = 0;
		}
		//talkln ("created histogram with " + String.valueOf(binCt) + " bins");
	}
	
	public void clearBins () {
		ptCt = 0;
		passCt = 0;
		for (int i=0;i<binCt;i++) {
			binSums[i] = 0;
		}
	}
	
	public void nullLinks() {
		parent = null;
		binIntervals = null;
		binSums = null;
		infoS = null;
	}
	
	public void setPath (String newPath) {
		path = newPath;
	}
	
	public static void setScaledNormMag (HistogramPlus [] hists) {
		// check all histograms in the list to see how they'll fall on a plot, fit scaled hists in that range
		scaledNormMag = 0;		// set to zero, be sure that it doesn't leave method as zero;
		HistogramPlus curHist;
		for (int i=0;i<hists.length;i++) {
			curHist = hists[i];
			if (!curHist.reportScaled) { 	// figure range by those NOT to be scaled
				if (!curHist.sumInfoCurrent) { curHist.calcSumInfo(); } // update summary info if not current
				if (curHist.reportSum) { scaledNormMag = curHist.maxMagVal; }
				if (curHist.reportNorm) { scaledNormMag = curHist.maxMagVal/curHist.ptCt; }
				if (curHist.reportAve) { scaledNormMag = curHist.maxMagVal/curHist.passCt; }
			}
		}
		if (scaledNormMag == 0) { scaledNormMag = 1.0; } // reset to default if not figured in this method
	}
	
	public void addPass () {
		passCt++;
	}
	
	public double getInterval (int index) {
		return binIntervals[index];
	}
	
	public double getCtVal (int index) {
		return binSums[index];
	}
	
	public double getNormCtVal (int index) {
		return binSums[index]/ptCt;
	}
	
	public double getScaledCtVal (int index) {
		if (!sumInfoCurrent) { calcSumInfo(); }
		return scaledNormMag*binSums[index]/maxMagVal;
	}
	
	public double getAveCtVal (int index) {
		return binSums[index]/passCt;
	}
	
	public void calcSumInfo() {
		maxVal= binSums[0];
		minVal = maxVal;
		maxMagVal = maxVal;
		for (int i=1;i<binCt;i++) {
			if (binSums[i] < minVal) { 
				minVal = binSums[i]; 
				if (Math.abs(minVal) > maxMagVal) { maxMagVal = Math.abs(minVal); }
			}
			if (binSums[i] > maxVal) { 
				maxVal = binSums[i]; 
				if (Math.abs(maxVal) > maxMagVal) { maxMagVal = Math.abs(maxVal); }
			}
		}
		sumInfoCurrent = true;
	}
	
	public void addValue (int index, double newVal) {
		boolean sumInfoCurrent = false;
		binSums[index] += newVal;
		ptCt++;
	}
	
	public void addValue (int index, int newIntVal) {
		binSums[index] += newIntVal;
		ptCt += newIntVal;
	}
	
	public void addRandom () {
		double randNum = Env.mtRNG.nextDouble();
		int randBin = (int) (Math.floor(randNum*binCt));
		binSums[randBin]++;
		ptCt++;
	}
	
	public void decRandom () {
		double randNum = Env.mtRNG.nextDouble();
		int randBin = (int) (Math.floor(randNum*binCt));
		binSums[randBin] += -1;
		ptCt += -1;
	}

	
	public void addValue (double xPos) {
		boolean sumInfoCurrent = false;
		int newValInd = (int) (Math.floor((xPos-startVal)/stepVal));
		// check if value in tail
		if (xPos >= stopVal) { 
			//talkln ("pt in tail");
			if (tossTail) {
				return;					// reject this pt
			} else {
				newValInd = binCt-1;	// add tail pt to last bin
			}
		}
		
		// check if value in head
		if (xPos < startVal) {
			//talkln ("pt in head");
			if (tossHead) {
				return;					// reject this pt
			} else {
				newValInd = 0;			// add head pt to first bin
			}
		}
		//talkln ("adding to index " + String.valueOf(newValInd));
		binSums[newValInd]++;
		ptCt++;
	}
	
	//variant of method to plot values at correct xvalue on xaxis - see Listeria.mkActAProbsHisto()
	public void addValue (double xPos, double valToAdd) {
		
		//xPos is position in range
		//valToAdd is value to be put into bin
		boolean sumInfoCurrent = false;
		int xPosInd = (int) (Math.floor((xPos-startVal)/stepVal));
		// check if value in tail
		if (xPos >= stopVal) { 
			//talkln ("pt in tail");
			if (tossTail) {
				return;					// reject this pt
			} else {
				xPosInd = binCt-1;	// add tail pt to last bin
			}
		}
		
		// check if value in head
		if (xPos < startVal) {
			//talkln ("pt in head");
			if (tossHead) {
				return;					// reject this pt
			} else {
				xPosInd = 0;			// add head pt to first bin
			}
		}
		//talkln ("adding to index " + String.valueOf(newValInd));
		binSums[xPosInd]= valToAdd;
		ptCt++;
	}
	
	public boolean decValue (double decVal) {
		int decValInd = (int) (Math.floor((decVal-startVal)/stepVal));
		// check if value in tail
		if (decVal >= stopVal) { return false; }
		
		// check if value in head
		if (decVal < startVal) { return false; }
		
		// check if we can decrement that bin
		if (binSums[decValInd] > 0) {
			binSums[decValInd]+= -1;
			ptCt += -1;
			return true;
		} else {
			return false;
		}
	}
	
	public double getValue (double val) {
		int valInd = (int) (Math.floor((val-startVal)/stepVal));
		// check if value in tail
		if (val >= stopVal) { return 0; }
		
		// check if value in head
		if (val < startVal) { return 0; }
		
		// else return value in bin
		return binSums[valInd];
	}
	
	
	public void addHisto (HistogramPlus2 newHist) {
		boolean sumInfoCurrent = false;
		if (binCt != newHist.binCt) {
			talkln (" **** Error: Can't add histograms of different size");
			return;
		}
		ptCt += newHist.ptCt;
		passCt += newHist.passCt;
		for (int i=0;i<binCt;i++) {
			binSums[i] += newHist.binSums[i];
		}
	}
	
	public void printIntervalTitle (PrintWriter pw, String sepString) {
		pw.print(intervalTitle + sepString);
	}
	
	public void printIntervalTitle () { printIntervalTitle (histoPW, HistogramPlus2.sepString); }
	
	
	public void printInterval (PrintWriter pw, String sepString, int index) {
		String toPrint = String.valueOf(getInterval(index)) + sepString;
		//talkln ("toPrint is " + toPrint);
		pw.print(toPrint);
	}
	
	public void printInterval (int i) { printInterval (histoPW, HistogramPlus2.sepString, i); }
	
	public void printBinTitles (PrintWriter pw, String sepString) {
		if (reportSum) { pw.print(name + unitsString + sepString); }
		if (reportNorm) { pw.print(name + "/ptCt" + sepString); }
		if (reportAve) { pw.print(name + "/passCt" + sepString); }
		if (reportScaled) { pw.print(name + "-scaled" + sepString); }
	}
	
	public void printBinTitles () { printBinTitles (histoPW, HistogramPlus2.sepString); }
	
	public void printBinSum (PrintWriter pw, String sepString, int index) {
		if (reportSum) { pw.print(String.valueOf(getCtVal(index)) + sepString); }
		if (reportNorm) { pw.print(String.valueOf(getNormCtVal(index)) + sepString); }
		if (reportAve) { pw.print(String.valueOf(getAveCtVal(index)) + sepString); }
		if (reportScaled) { pw.print(String.valueOf(getScaledCtVal(index)) + sepString); }
	}
	
	public void printBinSum (int i) { printBinSum (histoPW, HistogramPlus2.sepString, i); }
	
	public void writeToFile () {
		try { 
			if (path == null) { return; }
			if (!fileInitialized) {
				histoFile = new File (path + File.separatorChar + name);
				histoFW = new FileWriter(histoFile);
				histoPW = new PrintWriter(histoFW, true);
				fileInitialized = true;
				//	column headings
				printIntervalTitle(); 
				for (int i=0;i<binCt;i++) { printInterval(i); }
				histoPW.println("");
			}
			if (!histoFile.exists()) { return; }
			
			// data
			histoPW.print(String.valueOf(name + "@" + Env.simulationTime + " s") + sepString);
			for (int i=0;i<binCt;i++) { printBinSum(i); }
			histoPW.println("");
			
		} catch (IOException ioe) { talkln ("Error creating a histogram file"); }
	}
	
	public static void writeAllHistos () {
		for (int i=0;i<histCt;i++) {
			theHistos[i].writeToFile();
		}
	}
	
	public static void addHist (HistogramPlus2 newHist) {
		theHistos[histCt] = newHist;
		histCt++;
	}
	
	public String getBinString () {
		String binString = "[";
		for (int i=0;i<binCt;i++) {
			binString += (int)binSums[i];
			if (i<binCt-1) { binString += ","; }
		}
		binString += "]";
		return binString;
	}
	
	private static void talk (String info) {
		System.out.println(info);
	}
	
	private static void talkln (String info) {
		System.out.println(info);
	}
	
	
}