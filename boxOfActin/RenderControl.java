package boxOfActin;
import java.awt.*;
import javax.swing.*;
import java.io.*;
import java.text.DecimalFormat;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class RenderControl extends JFrame implements ActionListener {
	static BoxOfActin_Graphics parentSim;
	static String [] fileList;
	static int fileCt; 
	static int curFile;
	static boolean fileChanged = true;
	static boolean paused = true;
	static boolean toPNG = false;
	static boolean skip = false;
	static boolean gotoFile = false;
	static boolean rotate = false;
	static boolean endRotate =false;
	static int endRotateCt = 0;
	static double rotateAng = 1; // degrees
	static double endRotateAng = 10; // degree
	static String titleString = "Render Control: ";
	static String blankTitleString = "Use 'O' to render files....";
	static String toPNGPath = null;
	static String toPNGName = null;
	static String pausedString = "renderIsPaused";
	static int fileStep = 1;
	static RenderThread renderer = new RenderThread();
	static javax.swing.Timer renderTimer;
	static Object renderWaitO = new Object();
	DecimalFormat rotIdFormat = new DecimalFormat ("#000.#;#000.#");

	
	static JPanel buttonsPanel,renderPanel,renderPanel1,renderPanel2,renderPanel3,renderPanel4,renderPanel5,renderPanel6,renderPanel7,infoPanel;
	static BareButton stopB,playB,firstB,lastB,prevB,nextB,doneB,openB;
	static JLabel pngLoc,curRenderName,curRenderFrac;
	static JTextField skipField,gotoField,rotateField,endRotateThruField,endRotateByField;
	static JRadioButton pngB,skipB,gotoB,filROffB,monROffB,rotateB,endRotateB;

	public RenderControl (BoxOfActin_Graphics parentSim) {
		RenderControl.parentSim = parentSim;
		
		makeButtonsPanel();
		makeInfoPanel();
		makeRenderPanels();
		
		
		initialize();
		this.setTitle(titleString + blankTitleString);
		setSize(640,250);
		setResizable(false);
			
		//this.setLocation(parentSim.getLocation().x + Env.frameWidth.getIntValue()+5,0);
		this.setLocation(0,0);

	}
	
	public void initialize () {
		getContentPane().removeAll();
		//getContentPane().setLayout(new GridLayout(3,1));
		getContentPane().setLayout(new BorderLayout());
		getContentPane().add (renderPanel,BorderLayout.CENTER);
		getContentPane().add (buttonsPanel,BorderLayout.SOUTH);
		//getContentPane().add (paramPanel, BorderLayout.CENTER);
		//getContentPane().add (applyPanel, BorderLayout.SOUTH);
	
	}
	
	public void startRenderTimer() {
		int delay = 1000; //milliseconds
		ActionListener taskPerformer = new ActionListener() {
			String fileToRender,lastName;
			public void actionPerformed(ActionEvent evt) {
				if (BoxOfActin_Graphics.theCanvas.isRendering()) { return; }
				if (fileChanged) { 
					if (gotoFile) { 
						curFile = getGotoFile(); 
						gotoFile = false;
					}
					lastName = fileList[curFile];
					fileToRender = Env.fromQKFilePath + File.separator + lastName;
					//System.out.println(String.valueOf(rotIdFormat.format(endRotateCt)));
					
					lastName = lastName.substring(0,lastName.length()-3);	// trim off .qk
					lastName+=String.valueOf(rotIdFormat.format(endRotateCt)); 
					
					curRenderFrac.setText(" (" + String.valueOf(curFile+1) + "/" + String.valueOf(fileCt) + ") ");
					curRenderName.setText(lastName);
					renderer.render(fileToRender,lastName); 
					fileChanged = false;
				}
				
				if (!paused) {
					rotateAng = getRotAngle();
					endRotateAng = getEndRotByAngle();
					if (curFile < fileCt-1 | (endRotate & endRotateCt < getEndRotThruAngle()/endRotateAng)) { 
						if (skip) { curFile += getSkip(); } else { curFile++; }
						if (curFile > fileCt-1) { curFile = fileCt-1; endRotateCt++;}
						fileChanged = true;
					} else {
						paused = true;
						fileChanged = false;
						endRotateCt = 0;
					}
				}
		    }
		};
		renderTimer = new javax.swing.Timer(delay, taskPerformer);
		renderTimer.start();
	}
	
	static class RenderThread extends Thread {
		
		public void run () {
		}
		
		public void render(String fileToRender, String lastName) {
			String curFileToRender = fileToRender.substring(0);
			if (rotate) { BoxOfActin.boaGraphics.rotateViewY(rotateAng); }
			if (endRotate & endRotateCt > 0) { BoxOfActin.boaGraphics.rotateViewY(endRotateAng); }
			System.out.println("Beginning render " + curFileToRender + ".....");
			if ((toPNG) & (toPNGPath != null)) {
				BoxOfActin_Graphics.theCanvas.setAltCapture(lastName);
				BoxOfActin_Graphics.theCanvas.writePNG_ = true;
			}
			FileOps.loadQuickPicture(curFileToRender);
			BoxOfActin_Graphics.updateQKBugScene();
			while ((BoxOfActin_Graphics.theCanvas.isRendering()) | (BoxOfActin_Graphics.theCanvas.isWritingJPEG())) {
				try { Thread.sleep(200); } catch (InterruptedException e) { System.out.println("error sleeping in render"); }
			}
			System.out.println("Done with render.");
			System.out.println("");
		}
		
	}
	
	public void openRender () {
		BoxOfActin_Graphics.prepForQKRender();
		if (Env.fromQKFilePath != null) {
			setTitle (titleString + Env.fromQKFilePath);
			startRender();
		}
	}
	
	public void startRender () {
		resetRender();
		if  (fileCt == 0) { 
			System.out.println("No '.qk' files found at that location, try again");
			return;
		}
		BoxOfActin_Graphics.clearForRender();
		startRenderTimer();
	}
			
	public void resetRender () {
		File qkDir = new File(Env.fromQKFilePath);
		fileList = FileOps.getQKFileList(qkDir);  // get files from directory ending in .qk
		fileCt = fileList.length;
		curFile = 0;
		fileChanged = true;
		paused = true;
	}
	
	public void timerOn() {
		if (renderTimer==null) { return; }
		if (!renderTimer.isRunning()) { renderTimer.start(); }
	}
	
	public void timerOff() {
		if (renderTimer==null) { return; }
		if (renderTimer.isRunning()) { renderTimer.stop(); }
	}
	
	public int getSkip() {
		String skipString = skipField.getText();
		int skip = 1;
		try {
			Double valD = Double.valueOf(skipString);
			double val = valD.doubleValue();
			skip = (int)val;
		} catch (java.lang.NumberFormatException nfe) {
			System.out.println("Invalid field entry in skip field.... setting to 1");
			skip = 1;
		}
		if (skip < 1) { skip = 1; }
		skipField.setText(String.valueOf(skip));
		return skip;
	}
	
	public int getGotoFile() {
		String gotoString = gotoField.getText();
		int gotoF = curFile+1; // +1 because of 0 index start
		try {
			Double valD = Double.valueOf(gotoString);
			double val = valD.doubleValue();
			gotoF = (int)val;
		} catch (java.lang.NumberFormatException nfe) {
			System.out.println("Invalid field entry in goto field.... ignoring");
			gotoF = curFile+1;
		}
		if (gotoF < 1) { gotoF = 1; }
		if (gotoF > fileCt) { gotoF = fileCt; }
		gotoField.setText(String.valueOf(gotoF));
		return gotoF-1;	// return gotoF-1 because of 0 index start
	}
	
	public double getRotAngle() {
		double rotVal = 1;
		String rotAngString = rotateField.getText();
		rotAngString = rotAngString.substring(0,rotAngString.length()-1);	// trim off degree symbol
		try {
			Double valD = Double.valueOf(rotAngString);
			rotVal = valD.doubleValue();
		} catch (java.lang.NumberFormatException nfe) {
			System.out.println("Invalid field entry in rotate field.... setting to 1 Degree");
			rotVal = 1;
		}
		rotateField.setText(String.valueOf(rotVal) + "�");
		return rotVal;
	}
	
	public double getEndRotByAngle() {
		double rotVal = 1;
		String rotAngString = endRotateByField.getText();
		rotAngString = rotAngString.substring(0,rotAngString.length()-1);	// trim off degree symbol
		try {
			Double valD = Double.valueOf(rotAngString);
			rotVal = valD.doubleValue();
		} catch (java.lang.NumberFormatException nfe) {
			System.out.println("Invalid field entry in rotate field.... setting to 1 Degree");
			rotVal = 1;
		}
		endRotateByField.setText(String.valueOf(rotVal) + "�");
		return rotVal;
	}
	
	public double getEndRotThruAngle() {
		double rotVal = 1;
		String rotAngString = endRotateThruField.getText();
		rotAngString = rotAngString.substring(0,rotAngString.length()-1);	// trim off degree symbol
		try {
			Double valD = Double.valueOf(rotAngString);
			rotVal = valD.doubleValue();
		} catch (java.lang.NumberFormatException nfe) {
			System.out.println("Invalid field entry in rotate field.... setting to 1 Degree");
			rotVal = 1;
		}
		endRotateThruField.setText(String.valueOf(rotVal) + "�");
		return rotVal;
	}
	
	public void actionPerformed (ActionEvent e) {
		String act = e.getActionCommand();
		
		if (act.equals("Stop")) {
			paused = true;
		}
		
		if (act.equals("Play")) {
			paused = false;
			fileChanged = true;
			if (curFile >= fileCt-1) { curFile = 0; }
			timerOn();
		}
		
		if (act.equals("First")) {
			curFile = 0;
			paused = true;
			fileChanged = true;
			timerOn();
		}
		
		if (act.equals("Last")) {
			curFile = fileCt-1;
			paused = true;
			fileChanged = true;
			timerOn();
		}
		
		if (act.equals("Prev")) {
			if (curFile > 0) { curFile--; }
			fileChanged = true;
			timerOn();
		}
		
		if (act.equals("Next")) {
			if (curFile < fileCt-1) { curFile++; }
			fileChanged = true;
			timerOn();
		}
		
		if (act.equals("Open")) {
			BoxOfActin.setPaused();
			openRender();
		}
		
		if (act.equals("Done")) {
			timerOff();
			this.setVisible(false);
		}
		
		if (act.equals("WritePNGs")) {
			toPNG = pngB.isSelected();
			if (toPNG) { prepForJPEGWriting(); }
		}
		
		if (act.equals("FilRenderOff")) {
			Env.filRenderOff = filROffB.isSelected();
			System.out.println ("filRenderOff is " + Env.filRenderOff);
		}
		
		if (act.equals("MonRenderOff")) {
			Env.noMonomersSimd.setActive(monROffB.isSelected());
			System.out.println ("monRenderOff is " + Env.noMonomersSimd.isActive());
		}
		
		if (act.equals("Rotate")) {
			rotate = rotateB.isSelected();
			rotateAng = getRotAngle();
		}
		
		if (act.equals("EndRotate")) {
			endRotate = endRotateB.isSelected();
			endRotateAng = getEndRotByAngle();
		}
		
		if (act.equals("Skip")) {
			skip = skipB.isSelected();
		}

		if (act.equals("Goto")) {
			gotoFile = true;
			gotoB.setSelected(false);
			fileChanged = true;
		}
	}
	
	public void makeRenderPanels () {
		renderPanel = new JPanel();
		renderPanel.setBackground(Env.controlBackColor);
		
		renderPanel1 = new JPanel();
		renderPanel1.setBackground(Env.controlBackColor);
		
		renderPanel2 = new JPanel();
		renderPanel2.setBackground(Env.controlBackColor);
		
		renderPanel3 = new JPanel();
		renderPanel3.setBackground(Env.controlBackColor);
		
		renderPanel4 = new JPanel();
		renderPanel4.setBackground(Env.controlBackColor);
		
		renderPanel5 = new JPanel();
		renderPanel5.setBackground(Env.controlBackColor);
		
		renderPanel6 = new JPanel();
		renderPanel6.setBackground(Env.controlBackColor);
		
		renderPanel7 = new JPanel();
		renderPanel7.setBackground(Env.controlBackColor);
		
		pngB = new JRadioButton ("Write PNGs to");
		pngB.setActionCommand("WritePNGs");
		pngB.addActionListener(this);
		pngB.setBackground(Env.controlBackColor);
		pngB.setForeground(Env.controlForeColor);
		pngB.setSelected(toPNG);
		pngLoc = new JLabel ("                    ");
		pngLoc.setBackground(Env.controlBackColor);
		pngLoc.setForeground(Env.controlForeColor);
		
		rotateB = new JRadioButton ("Rotate");
		rotateB.setActionCommand("Rotate");
		rotateB.addActionListener(this);
		rotateB.setBackground(Env.controlBackColor);
		rotateB.setForeground(Env.controlForeColor);
		rotateB.setSelected(rotate);
		rotateField = new JTextField ("1�",10);
		rotateField.addActionListener(this);
		rotateField.setActionCommand("Rotate");
		rotateField.setBackground(Env.controlBackColor);
		rotateField.setForeground(Env.controlForeColor);
		rotateField.setBorder(BorderFactory.createEmptyBorder());
		rotateField.setEditable(true);
		
		filROffB = new JRadioButton ("Filament Render OFF");
		filROffB.setActionCommand("FilRenderOff");
		filROffB.addActionListener(this);
		filROffB.setBackground(Env.controlBackColor);
		filROffB.setForeground(Env.controlForeColor);
		filROffB.setSelected(Env.filRenderOff);
		
		monROffB = new JRadioButton ("Monomer Render OFF");
		monROffB.setActionCommand("MonRenderOff");
		monROffB.addActionListener(this);
		monROffB.setBackground(Env.controlBackColor);
		monROffB.setForeground(Env.controlForeColor);
		monROffB.setSelected(Env.noMonomersSimd.isActive());
		
		skipB = new JRadioButton ("Skip every");
		skipB.setActionCommand("Skip");
		skipB.addActionListener(this);
		skipB.setBackground(Env.controlBackColor);
		skipB.setForeground(Env.controlForeColor);
		skipB.setSelected(skip);
		skipField = new JTextField ("1",4);
		skipField.setBackground(Env.controlBackColor);
		skipField.setForeground(Env.controlForeColor);
		skipField.setBorder(BorderFactory.createEmptyBorder());
		skipField.setEditable(true);
		
		gotoB = new JRadioButton("Goto file");
		gotoB.setActionCommand("Goto");
		gotoB.addActionListener(this);
		gotoB.setBackground(Env.controlBackColor);
		gotoB.setForeground(Env.controlForeColor);
		gotoField = new JTextField ("1",4);
		gotoField.setBackground(Env.controlBackColor);
		gotoField.setForeground(Env.controlForeColor);
		gotoField.setBorder(BorderFactory.createEmptyBorder());
		gotoField.setEditable(true);
		
		endRotateB = new JRadioButton("End Rotate Thru");
		endRotateB.setActionCommand("EndRotate");
		endRotateB.addActionListener(this);
		endRotateB.setBackground(Env.controlBackColor);
		endRotateB.setForeground(Env.controlForeColor);
		endRotateThruField = new JTextField ("90�",4);
		endRotateThruField.setBackground(Env.controlBackColor);
		endRotateThruField.setForeground(Env.controlForeColor);
		endRotateThruField.setBorder(BorderFactory.createEmptyBorder());
		endRotateThruField.setEditable(true);
		JLabel endRotateByLabel = new JLabel("by"); 
		endRotateByLabel.setBackground(Env.controlBackColor);
		endRotateByLabel.setForeground(Env.controlForeColor);
		endRotateByField = new JTextField ("10�",3);
		endRotateByField.setBackground(Env.controlBackColor);
		endRotateByField.setForeground(Env.controlForeColor);
		endRotateByField.setBorder(BorderFactory.createEmptyBorder());
		endRotateByField.setEditable(true);
	
		renderPanel1.setLayout(new FlowLayout(FlowLayout.LEFT));
		renderPanel1.add(pngB);
		renderPanel1.add(pngLoc);
		
		renderPanel2.setLayout(new FlowLayout(FlowLayout.LEFT));
		renderPanel2.add(filROffB);
		
		renderPanel3.setLayout(new FlowLayout(FlowLayout.LEFT));
		renderPanel3.add(monROffB);
		
		renderPanel4.setLayout(new FlowLayout(FlowLayout.LEFT));
		renderPanel4.add(rotateB);
		renderPanel4.add(rotateField);
		
		renderPanel5.setLayout(new FlowLayout(FlowLayout.LEFT));
		renderPanel5.add(skipB);
		renderPanel5.add(skipField);
		
		renderPanel6.setLayout(new FlowLayout(FlowLayout.LEFT));
		renderPanel6.add(gotoB);
		renderPanel6.add(gotoField);
		
		renderPanel7.setLayout(new FlowLayout(FlowLayout.LEFT));
		renderPanel7.add(endRotateB);
		renderPanel7.add(endRotateThruField);
		renderPanel7.add(endRotateByLabel);
		renderPanel7.add(endRotateByField);
		
		
		renderPanel.setLayout(new GridLayout(8,1));
		renderPanel.add(infoPanel);
		renderPanel.add(renderPanel1);
		renderPanel.add(renderPanel2);
		renderPanel.add(renderPanel3);
		renderPanel.add(renderPanel4);
		renderPanel.add(renderPanel5);
		renderPanel.add(renderPanel6);
		renderPanel.add(renderPanel7);
	}
	
	public void makeInfoPanel () {
		infoPanel = new JPanel();
		infoPanel.setBackground(Env.controlBackColor);
		
		JLabel curRenderLabel = new JLabel("Current file: ");
		curRenderLabel.setBackground(Env.controlBackColor);
		curRenderLabel.setForeground(Env.controlForeColor);
		
		curRenderFrac = new JLabel("");
		curRenderFrac.setBackground(Env.controlBackColor);
		curRenderFrac.setForeground(Env.controlForeColor);
		
		curRenderName =  new JLabel("");
		curRenderName.setBackground(Env.controlBackColor);
		curRenderName.setForeground(Env.controlForeColor);
		
		infoPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
		infoPanel.add(curRenderLabel);
		infoPanel.add(curRenderFrac);
		infoPanel.add(curRenderName);
	}
		
	
	public void makeButtonsPanel () {
		buttonsPanel = new JPanel();
		buttonsPanel.setBackground(Env.controlBackColor);
	
		firstB = new BareButton ("|--");
		firstB.setActionCommand("First");
		firstB.setToolTipText("Goto first frame");
		firstB.addActionListener(this);
		firstB.setEnabled(true);
		
		prevB = new BareButton ("<<");
		prevB.setActionCommand("Prev");
		prevB.setToolTipText("Previous frame");
		prevB.addActionListener(this);
		prevB.setEnabled(true);
		
		stopB = new BareButton ("�");
		stopB.setActionCommand("Stop");
		stopB.setToolTipText("Stop");
		stopB.addActionListener(this);
		stopB.setEnabled(true);
		
		playB = new BareButton (">");
		playB.setActionCommand("Play");
		playB.setToolTipText("Play");
		playB.addActionListener(this);
		playB.setEnabled(true);
		
		nextB = new BareButton (">>");
		nextB.setActionCommand("Next");
		nextB.setToolTipText("Next frame");
		nextB.addActionListener(this);
		nextB.setEnabled(true);
		
		lastB = new BareButton ("--|");
		lastB.setActionCommand("Last");
		lastB.setToolTipText("Goto last frame");
		lastB.addActionListener(this);
		lastB.setEnabled(true);
		
		JTextField blank = new JTextField("  ");
		blank.setBackground(Env.controlBackColor);
		blank.setBorder(BorderFactory.createEmptyBorder());
		
		openB = new BareButton ("O");
		openB.setActionCommand("Open");
		openB.setToolTipText("Open render");
		openB.addActionListener(this);
		openB.setEnabled(true);
		
		doneB = new BareButton ("X");
		doneB.setActionCommand("Done");
		doneB.setToolTipText("End render");
		doneB.addActionListener(this);
		doneB.setEnabled(true);
		
		buttonsPanel.setLayout(new GridLayout(1,9));
		buttonsPanel.add(firstB);
		buttonsPanel.add(prevB);
		buttonsPanel.add(stopB);
		buttonsPanel.add(playB);
		buttonsPanel.add(nextB);
		buttonsPanel.add(lastB);
		buttonsPanel.add(blank);
		buttonsPanel.add(openB);
		buttonsPanel.add(doneB);
	}
	
	public void prepForJPEGWriting () {
		toPNGPath = null;
		toPNGPath = FileOps.getDirectoryToSave("Create folder for PNG files ...", this);
		if (toPNGPath == null) { 
			toPNGName = null;
			pngLoc.setText("");
		} else {
			int lastbitIndex = toPNGPath.lastIndexOf(File.separator);
			toPNGName = toPNGPath.substring(lastbitIndex+1);
			BoxOfActin_Graphics.theCanvas.capturePath = toPNGPath;
			BoxOfActin_Graphics.theCanvas.captureName = toPNGName;
			pngLoc.setText(toPNGPath);
		}
	}
	
}
	
