package boxOfActin;
/*
	HistoPlotPanel ... JPanel for plotting HistogramPlus
*/

import java.awt.*;
import java.awt.image.*;
import javax.swing.*;
import java.text.*;
import java.io.*;

public class HistoPlotPanel extends JPanel {
	static BufferedImage theImage;
	static Graphics2D thePalate;
	Dimension curSize;
	double xMin,xMax,yMin,yMax;
	int xPix, yPix;
	double xPixFac, yPixFac;
	
	// painting options
	int [] insets = new int [] {30,30,20,70};
	Color backColor = Env.controlBackColor;
	Color boxColor = Env.controlForeColor;
	Color ticColor = Env.controlForeColor;
	Color histColor = Env.controlForeColor;
	Color nameColor = Env.controlForeColor;
	int ticSpace = 5;
	int ticLabelSpace = 2;	// label every # of tics
	int yPixelPad = 5;
	static DecimalFormat intervalFormat = new DecimalFormat("#0.00;#0.00");
	static Font labelFont = new Font(null,Font.PLAIN,10);
	static Font nameFont = new Font(null,Font.PLAIN,12);
	
	Parameter widthParam, heightParam;


	public HistoPlotPanel (Parameter widthParam, Parameter heightParam) {
		this.widthParam = widthParam;
		this.heightParam = heightParam;
	}

	public HistoPlotPanel () {
		
	}
	
	public void initialize (int width, int height) {
		initialize(new Dimension(width,height));
	}
	
	public void initialize (Dimension curSize) {
		this.curSize = curSize;
		xPix = curSize.width-insets[0]-insets[1];
		yPix = curSize.height-insets[2]-insets[3];
		setSize(curSize);
		theImage = (BufferedImage)createImage(curSize.width,curSize.height);
		thePalate = (Graphics2D)theImage.getGraphics();
		thePalate.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
		thePalate.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		clearImage();
	}
	
	public void initializeFromParams () {
		initialize(widthParam.getIntValue(),heightParam.getIntValue());
	}
	
	public void autoScaleY (HistogramPlus hist) {
		yMin = 0;		// for now, minimum zero assumed
		yMax = 0;
		for (int i=0;i<hist.binCt;i++) {
			if (hist.binSums[i] > yMax) { yMax = hist.binSums[i]; }
		}
	}
	
	public void autoScaleX (HistogramPlus hist) {
		xMin = 1e32;
		xMax = -1e32;
		for (int i=0;i<hist.binCt;i++) {
			if (hist.binIntervals[i] > xMax) { xMax = hist.binIntervals[i]; }
			if (hist.binIntervals[i] < xMin) { xMin = hist.binIntervals[i]; }
		}
	}
	
	public boolean isCurrentWithParams() {
		if (theImage.getWidth() != widthParam.getIntValue()) { return false; }
		if (theImage.getHeight() != heightParam.getIntValue()) { return false; }
		return true;
	}
	
	public void makeCurrentWithParams() {
		initializeFromParams();
	}
	
	public void autoScale (HistogramPlus [] hists) {
		yMin = 0;		// for now, minimum zero assumed
		yMax = 0;
		for (int j=0;j<hists.length;j++) {
			for (int i=0;i<hists[j].binCt;i++) {
				if (hists[j].binSums[i] > yMax) { yMax = hists[j].binSums[i]; }
			}
		}
		
		xMin = 1e32;
		xMax = -1e32;
		for (int i=0;i<hists[0].binCt;i++) {
			if (hists[0].binIntervals[i] > xMax) { xMax = hists[0].binIntervals[i]; }
			if (hists[0].binIntervals[i] < xMin) { xMin = hists[0].binIntervals[i]; }
		}
		talkln ("yMax = " + String.valueOf(yMax));
	}
	
	public void setYScale (double scaleFac) {
		yMax = scaleFac;
	}
	
	public void setPixFactors () {
		xPixFac = (xMax-xMin)/(double)xPix;
		yPixFac = (yMax-yMin)/(double)(yPix-yPixelPad);
		//talkln ("xPix = " + String.valueOf(xPix) + " ; range = " + String.valueOf(xMax-xMin));
		//talkln ("yPix = " + String.valueOf(yPix) + " ; range = " + String.valueOf(yMax-yMin));
	}
	
	public void showName (String nameString, HistogramPlus hist) {
		thePalate.setColor(nameColor);
		thePalate.setFont(nameFont);
		int xName = insets[0];
		int yName = curSize.height - insets[3] + 30;
		thePalate.drawString(nameString,xName,yName);
		thePalate.drawString("(binCt: " + hist.binCt + ", binInterval: " + hist.stepVal + ")",xName,yName+15);
	}
	
	public void plotYTics () {
		int curX = insets[0];
		int curY = insets[2] -5;
		thePalate.setColor(ticColor);
		thePalate.setFont(labelFont);
		thePalate.drawString(String.valueOf(yMax),curX,curY);
	}
	
	public void updateHist (HistogramPlus hist) {
		clearImage();
		showName(hist.name + " " + hist.unitsString, hist);
		if (Env.fixedHistScale.isActive()) {
			autoScaleX(hist);
			setYScale(Env.fixedHistScale.getValue());
		} else {
			autoScaleX(hist);
			autoScaleY(hist);
		}
		if (Env.showYHistTics.isActive()) { plotYTics(); } 
		plotHist(hist);
	}
	
	public void plotHist (HistogramPlus hist) {
		setPixFactors();
		// plot pixels loop
		int curY = 0,curX = 0,lastY = 0,lastX = 0;
		int ticCt = ticSpace;
		int ticLabelCt = ticLabelSpace;
		for (int i=0;i<hist.binCt;i++) {
			curY = (int)(insets[2] + yPix - (hist.binSums[i] - yMin)/yPixFac);
			curX = (int)(insets[0] + (hist.binIntervals[i] - xMin)/xPixFac);
			//talkln ("pix " + String.valueOf(i) + " : " + String.valueOf(curX) + "," + String.valueOf(curY));
			if (i != 0) {
				plotHistPoint(lastX,lastY,curX,curY);
			}
			
			if ((ticCt == ticSpace) | (i == hist.binCt-1)) {
				boolean printLabel = false;
				if ((ticLabelCt == ticLabelSpace) | (i == hist.binCt-1)) {
					printLabel = true;
					ticLabelCt = 0;
				}
					
				plotHistTic(curX, hist.binIntervals[i],printLabel);
				ticCt = 0;
				ticLabelCt++;
			}
			lastX = curX;
			lastY = curY;
			ticCt++;
		}
		repaint();
	}
	
	private void plotHistPoint (int x1,int y1,int x2,int y2) {
		thePalate.setColor(histColor);
		if (Env.flatTops.isActive()) {
			thePalate.drawLine(x1,y1,x1,y2); // vertical
			thePalate.drawLine(x1,y2,x2,y2); // flat
		} else {
			thePalate.drawLine(x1,y1,x2,y2); // simple line between points
		}
	}
	
	private void plotHistTic (int x1, double value, boolean printVal) {
		int y1 = yPix + insets[2];
		int y2 = y1 + 5;
		thePalate.setColor(ticColor);
		thePalate.drawLine(x1,y1,x1,y2);
		if (printVal) {
			int xText = x1 - 10;
			int yText = y2 + 10;
			String label = String.valueOf(intervalFormat.format(value));
			thePalate.setFont(labelFont);
			thePalate.drawString(label,xText,yText);
		}
	}
	
	public BufferedImage getCurrentImage() {
		return theImage;
	}
	
	
	public void update(Graphics g) {
		paint(g);
	}
	
	public void paint (Graphics g) {
		//g.drawImage(theImage,0,0,null);
	}
	
	public void eraseGraphBox () {
		thePalate.setColor(backColor);
		thePalate.setStroke(new BasicStroke(2.0f));
		thePalate.drawRect(insets[0],insets[2],xPix,yPix);
		thePalate.setStroke(new BasicStroke());
	}
	
	public void showGraphBox () {
		thePalate.setColor(boxColor);
		thePalate.setStroke(new BasicStroke(2.0f));
		thePalate.drawRect(insets[0],insets[2],xPix,yPix);
		thePalate.setStroke(new BasicStroke());
	}
	
	public void clearImage() {
		setBackground(Color.black);
		thePalate.setColor(backColor);
		thePalate.fillRect(0,0,curSize.width,curSize.height);
		if (Env.showHistBox.isActive()) { showGraphBox(); }
	}
	
	private static void talkln (String info) {
		System.out.println(info);
	}
	
	private static void talk (String info) {
		System.out.println(info);
	}
}