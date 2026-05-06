package boxOfActin;
/* 
	HistogramPlus ...  stores and adds values for histogram
*/

import javax.swing.*;
import java.io.*;

public class HistogramPlus {
	JFrame parent;
	int binCt, ptCt, passCt;
	double startVal,stopVal,stepVal;
	double minVal,maxVal,maxMagVal;
	double [] binIntervals;
	double [] binSums;
	String [] infoS;
	String intervalTitle = "intervals";
	String unitsString = "";
	String path;
	String name;
	boolean sumInfoCurrent = false;
	boolean reportSum = true;
	boolean reportNorm = false;
	boolean reportAve = false;
	boolean reportScaled = false;
	Object histLock = new Object();
	
	Parameter binCtParam,rStartParam,rStopParam,keepHeadsParam,keepTailsParam;
	
	static double scaledNormMag = 1.0;		// mag of range in which to scale histograms
	
	public HistogramPlus (Parameter binCtParam, Parameter rStartParam, Parameter rStopParam, Parameter keepHeadsParam, Parameter keepTailsParam, String path, String name, JFrame parent) {
		this.parent = parent;
		this.binCtParam = binCtParam;
		this.rStartParam = rStartParam;
		this.rStopParam = rStopParam;
		this.keepHeadsParam = keepHeadsParam;
		this.keepTailsParam = keepTailsParam;
		
		this.binCt = binCtParam.getIntValue();
		this.startVal = rStartParam.getValue();
		this.stopVal = rStopParam.getValue();
		this.path = path;
		this.name = name;
		
		initialize();
	}
	
	public void initialize () {
		binSums = new double[binCt];
		binIntervals = new double[binCt];
		stepVal = (stopVal-startVal)/binCt;
		ptCt = 0;
		passCt = 0;
		for (int i=0;i<binCt;i++) {
			binIntervals[i] = i*stepVal + startVal;
			binSums[i] = 0;
		}
	}
	
	public void makeCurrentWithParams () {
		this.binCt = binCtParam.getIntValue();
		this.startVal = rStartParam.getValue();
		this.stopVal = rStopParam.getValue();
		initialize();
	}
	
	public boolean isCurrentWithParams () {
		if (binCt != binCtParam.getIntValue()) { return false; }
		if (Math.abs(startVal-rStartParam.getValue()) > 1e-5) { return false; }
		if (Math.abs(stopVal-rStopParam.getValue()) > 1e-5) { return false; }
		return true;
	}
	
	public void clearBins () {
		ptCt = 0;
		passCt = 0;
		for (int i=0;i<binCt;i++) {
			binSums[i] = 0;
		}
	}
	
	public void changeBinCt (int delta) {
		synchronized (histLock) {
			binCt+= delta;
			initialize();
		}
	}
	
	public void changeRange (double start, double stop) {
		synchronized (histLock) {
			this.startVal = start;
			this.stopVal = stop;
			initialize();
		}
	}
	
	public void nullLinks() {
		parent = null;
		binIntervals = null;
		binSums = null;
		infoS = null;
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
		sumInfoCurrent = false;
		binSums[index] += newVal;
		ptCt++;
	}
	
	public void addValue (double newVal) {
		sumInfoCurrent = false;
		int newValInd = (int) (Math.floor((newVal-startVal)/stepVal));
		// check if value in tail
		if (newVal >= stopVal) { 
			//talkln ("pt in tail");
			if (!keepTailsParam.isActive()) {
				return;					// reject this pt
			} else {
				newValInd = binCt-1;	// add tail pt to last bin
			}
		}
		
		// check if value in head
		if (newVal < startVal) {
			//talkln ("pt in head");
			if (!keepHeadsParam.isActive()) {
				return;					// reject this pt
			} else {
				newValInd = 0;			// add head pt to first bin
			}
		}
		//talkln ("adding to index " + String.valueOf(newValInd));
		binSums[newValInd]++;
		ptCt++;
	}
	
	public void addValue (double newVal, double incVal) {
		sumInfoCurrent = false;
		int newValInd = (int) (Math.floor((newVal-startVal)/stepVal));
		// check if value in tail
		if (newVal >= stopVal) { 
			//talkln ("pt in tail");
			if (!keepTailsParam.isActive()) {
				return;					// reject this pt
			} else {
				newValInd = binCt-1;	// add tail pt to last bin
			}
		}
		
		// check if value in head
		if (newVal < startVal) {
			//talkln ("pt in head");
			if (!keepHeadsParam.isActive()) {
				return;					// reject this pt
			} else {
				newValInd = 0;			// add head pt to first bin
			}
		}
		//talkln ("adding to index " + String.valueOf(newValInd));
		binSums[newValInd]+=incVal;
		ptCt+=incVal;
	}
	
	public void addHisto (HistogramPlus newHist) {
		sumInfoCurrent = false;
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
	
	public void printInterval (PrintWriter pw, String sepString, int index) {
		pw.print(String.valueOf(getInterval(index)) + sepString);
	}
	
	public void printBinTitles (PrintWriter pw, String sepString) {
		if (reportSum) { pw.print(name + unitsString + sepString); }
		if (reportNorm) { pw.print(name + "/ptCt" + sepString); }
		if (reportAve) { pw.print(name + "/passCt" + sepString); }
		if (reportScaled) { pw.print(name + "-scaled" + sepString); }
	}
	
	public void printBinSum (PrintWriter pw, String sepString, int index) {
		if (reportSum) { pw.print(String.valueOf(getCtVal(index)) + sepString); }
		if (reportNorm) { pw.print(String.valueOf(getNormCtVal(index)) + sepString); }
		if (reportAve) { pw.print(String.valueOf(getAveCtVal(index)) + sepString); }
		if (reportScaled) { pw.print(String.valueOf(getScaledCtVal(index)) + sepString); }
	}
	
	private static void talkln (String info) {
		System.out.println(info);
	}
	
	
}