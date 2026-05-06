package boxOfActin;
/* ValueTracker...an object that averages some value over time-steps */

import java.lang.Math;
import java.io.*;
import java.text.*;

public class ValueTracker {
	static final int DOUBLE_TYPE = 0;
	static final int PT3D_TYPE = 1;
	private int placeMark = 0;
	int type = DOUBLE_TYPE;
	private int stepsToTrack;
	double [] vals;
	private Pt3D [] ptVals;
	private Pt3D avePt = new Pt3D();
	private boolean trackFull = false;
	
	public ValueTracker (int stepsToTrack, int type) { 
		// the type is either 0 (default) meaning track values or 1 to track Pt3Ds
		this.stepsToTrack = stepsToTrack;
		if (type == PT3D_TYPE) {
			ptVals = new Pt3D [stepsToTrack];
			for (int i=0;i<stepsToTrack;i++) { ptVals[i] = new Pt3D(); }
			this.type = PT3D_TYPE;
		} else {
			vals = new double [stepsToTrack];
		}
	}
		
	public ValueTracker (int stepsToTrack) {
		this.stepsToTrack = stepsToTrack;
		vals = new double [stepsToTrack];
		for (int i=0;i<stepsToTrack;i++) { vals[i] = 0; }
	}
	
	public void registerValue (double newVal) {
		if (placeMark == stepsToTrack) { 
			placeMark = 0; 
			trackFull = true;	// set to true the first time the track is filled up
		}
		vals[placeMark] = newVal;
		placeMark++;
	}
	
	public void registerValue (Pt3D newPt3D) {
		ptVals[placeMark].copy(newPt3D);
		if (placeMark < (stepsToTrack-1)) { placeMark++; } else { placeMark = 0; trackFull = true;}
	}
	
	public double averageVal () {
		double ave = 0;
		int count = stepsToTrack;
		for (int i=0; i < count; i++) { ave += vals[i]; }
		ave = ave/count;
		return ave;
	}
	
	public double runningAverageVal () {
		double ave = 0;
		int count = stepsToTrack;
		if (!trackFull) { count = placeMark; }
		for (int i=0; i < count; i++) { ave += vals[i]; }
		ave = ave/count;
		return ave;
	}
	
	public double weightedAveVal () {
		double ave = 0;
		double weightFactor = 0;
		double weightTotal = 0;
		int weightMark = placeMark;
		for (int i=0; i < stepsToTrack; i++) { 
			if (weightMark == stepsToTrack) { weightMark = 0; }
			weightFactor = 1/((double)(stepsToTrack-i));
			weightTotal += weightFactor;
			ave += weightFactor * vals[weightMark];
			weightMark++;
		}
		avePt.scale(1/weightTotal);
		return ave;
	}
	
	public Pt3D averagePtVal () {
		avePt.zero();
		int count = stepsToTrack;
		if (!trackFull) { count = placeMark; }
		for (int i=0; i < count; i++) { avePt.inc(ptVals[i]); }
		avePt.scale(1.0/((double)count));
		return avePt;
	}
	
	public Pt3D weightedAvePtVal () {
		avePt.zero();
		double weightFactor = 0;
		double weightTotal = 0;
		int weightMark = placeMark;
		for (int i=0; i < stepsToTrack; i++) { 
			if (weightMark == stepsToTrack) { weightMark = 0; }
			weightFactor = 1/((double)(stepsToTrack-i));
			weightTotal += weightFactor;
			avePt.inc(weightFactor,ptVals[weightMark]); 
			weightMark++;
		}
		avePt.scale(1/weightTotal);
		return avePt;
	}
	
	public double sum () {
		double sumVal = 0;
		int count = stepsToTrack;
		if (!trackFull) { count = placeMark; }
		for (int i=0; i < count; i++) { sumVal += vals[i]; }
		return sumVal;
	}
	
	public double variance () {
		int count = stepsToTrack;
		if (!trackFull) { count = placeMark; }
		double aveVal = runningAverageVal();
		double numerator = 0;
		for (int i=0;i<count;i++) {
			numerator += Math.pow(vals[i]-aveVal,2);
		}

		return numerator/count;
	}
	
	public Pt3D varianceOfPt () {
		int count = stepsToTrack;
		if (!trackFull) { count = placeMark; }
		Pt3D avePtVal = averagePtVal();
		double numeratorX=0;
		double numeratorY=0;
		double numeratorZ=0;
		for (int i=0;i<count;i++) {
			numeratorX += Math.pow(ptVals[i].x-avePtVal.x,2);
			numeratorY += Math.pow(ptVals[i].y-avePtVal.y,2);
			numeratorZ += Math.pow(ptVals[i].z-avePtVal.z,2);
		}
		return new Pt3D (numeratorX/count,numeratorY/count,numeratorZ/count);
	}
	
	public void zero() {
		trackFull = false;
		placeMark = 0;
		switch(type) {
		case DOUBLE_TYPE:
			for (int i=0;i<stepsToTrack;i++) { vals[i]=0; }
			break;
		case PT3D_TYPE:
			for (int i=0;i<stepsToTrack;i++) { ptVals[i].zero(); }
			break;
		}
	}
			
	
}