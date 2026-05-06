package boxOfActin;
/*
 * InitControlFrame... vary initial conditions for the run
*/

import java.awt.*;
import java.awt.event.*;
import java.lang.Math;
import javax.swing.*;
import javax.swing.event.*;

import java.io.*;
import java.text.*;
import java.util.*;
import javax.media.j3d.*;

public class GraphicsControl extends JFrame implements ChangeListener, ActionListener, WindowListener, ComponentListener, KeyListener, MouseListener, MouseMotionListener, AdjustmentListener {
	static BoxOfActin_Graphics parentSim;
	static JPanel titlePanel,paramPanel,applyPanel;
	static BareButton cancelB,applyB;
	static ParamGui fWidthGui, fHeightGui;
	static ParamGui [] inits = new ParamGui[24];
	static int paramCt = 0;
	static int guiLineCt = 0;
	
	public GraphicsControl (BoxOfActin_Graphics parentSim) {
		this.parentSim = parentSim;
		
		makeParamPanel();
		makeApplyPanel();
		
		if (!Env.remote) {
			addKeyListener(this);
			addComponentListener(this);
			addMouseListener(this);
			addMouseMotionListener(this);
			addWindowListener(this);
			
			initialize();
			this.setTitle("Graphics & File Writing");
			Dimension prefDim = getPreferredSize();
			prefDim.height += (paramCt+1)*10;
			setSize(prefDim);
		}
		
	}
	
	public void initialize () {
		getContentPane().removeAll();
		getContentPane().setLayout(new BorderLayout());
		//getContentPane().add (titlePanel, BorderLayout.NORTH);
		getContentPane().add (paramPanel, BorderLayout.CENTER);
		getContentPane().add (applyPanel, BorderLayout.SOUTH);
	}
	
	public void setInfoFields () {
		for (int i=0;i<paramCt;i++) {
			inits[i].syncToParameter();
		}
	}
	
	public void updateInfoFields () {
		for (int i=0;i<paramCt;i++) {
			if (!inits[i].isInEdit()) {
				inits[i].syncToParameter();
			} else {
				editsOn();
			}
		}
	}
	
	public void refreshAll () {
		for (int i=0;i<paramCt;i++) { inits[i].syncToParameter(); }
	}
	
	public void revertAll () {
		for (int i=0;i<paramCt;i++) { inits[i].setToDefaultValue(); }
	}

	public void actionPerformed (ActionEvent e) {
		String act = e.getActionCommand();
		
		if (act.equals("Apply")) {
			for (int i=0;i<paramCt;i++) { inits[i].setValueFromGui(); }
			refreshAll();
			if (!parentSim.canvasSizeCurrent()) { parentSim.makeCanvasSizeCurrent(); }
			editsOff();
		}
		
		if (act.equals("Revert")) {
			revertAll();
			if (!parentSim.canvasSizeCurrent()) { parentSim.makeCanvasSizeCurrent(); }
			editsOff();
		}
		
		if (act.equals("Cancel")) {
			refreshAll();
			editsOff();
		}
		
	}
	
	public void stateChanged(ChangeEvent e) {
	    JSlider source = (JSlider)e.getSource();
	}
	
	public void adjustmentValueChanged (AdjustmentEvent e) {
	}
	
	
	public void windowActivated (WindowEvent e) {}
	public void windowClosing (WindowEvent e) {}
	public void windowDeactivated (WindowEvent e) {}
	public void windowDeiconified (WindowEvent e) {}
	public void windowIconified (WindowEvent e) {}
	public void windowOpened (WindowEvent e) {}
	public void windowClosed (WindowEvent e) {}
	
	public void keyReleased (KeyEvent e) {}
	public void keyTyped (KeyEvent e) {}
	public void keyPressed (KeyEvent e) {
		String act = e.getKeyText(e.getKeyCode());
	}
	
	public void mouseClicked (MouseEvent e) {}
	public void mouseEntered (MouseEvent e) {}
	public void mouseExited (MouseEvent e) {}
	public void mousePressed (MouseEvent e) {
		int xPos = e.getX();
		int yPos = e.getY();
	}
	
	public void mouseReleased (MouseEvent e) {}
	public void mouseDragged (MouseEvent e) {}
	public void mouseMoved (MouseEvent e) {}
	
	public void componentHidden (ComponentEvent e) {}
	public void componentShown (ComponentEvent e) {}
	public void componentMoved (ComponentEvent e) {}
	public void componentResized (ComponentEvent e) {}
	
	public void makeTitlePanel () {
		titlePanel = new JPanel();
		titlePanel.setBackground(Env.controlBackColor);
		JLabel titleLabel = new JLabel("Graphics");
		titleLabel.setForeground(Env.controlForeColor);
		titlePanel.add(titleLabel);
	}
	
	private void addParam (ParamGui newInit) {
		inits[paramCt] = newInit;
		inits[paramCt].addToPanel(paramPanel);
		paramCt++;
		guiLineCt++;
	}
	
	public void makeParamPanel() {
		paramPanel = new JPanel();
		paramPanel.setBackground(Env.controlBackColor);
		paramPanel.setLayout(new GridLayout(Env.maxLayout,3));
		
		addInertGuiHead(" Frame Size","","");
		
		fWidthGui = new ParamGui(Env.frameWidth);
		addParam(fWidthGui);
		fHeightGui = new ParamGui(Env.frameHeight);
		addParam(fHeightGui);
		
		addBlankHead();
		addInertGuiHead(" Misc. Render Adjustments","","");
		addParam(new ParamGui(Env.transScale));
		addParam(new ParamGui(Env.filRenderThicken));
		
		addBlankHead();
		addInertGuiHead(" Drawing & File Writing","","");
		
		addParam(new ParamGui(Env.drawInterval));
		addParam(new ParamGui(Env.toFileInterval));
		addParam(new ParamGui(Env.rotationPerWrite,ParamGui.CHECKBOX_ON,"Rotate by this angle after each image write"));

		addBlankHead();
		//addParam(new ParamGui(Env.jpegQuality));
		addParam(new ParamGui(Env.toQKFileInterval));
		addParam(new ParamGui(Env.remoteReportInterval));
		
		addBlankHead();
		addInertGuiHead(" Show","","");  
		
		addParam(new ParamGui(Env.showTime,ParamGui.CHECKBOX_ON,"Check to show simulation time"));
		addParam(new ParamGui(Env.showConc,ParamGui.CHECKBOX_ON,"Check to show Actin concentration"));
		addParam(new ParamGui(Env.showNonHydroConc,ParamGui.CHECKBOX_ON,"Check to show non-hydrolyzable Actin concentration"));
		addParam(new ParamGui(Env.showFilCt,ParamGui.CHECKBOX_ON,"Check to show filament count"));
		addParam(new ParamGui(Env.showFilSegCt,ParamGui.CHECKBOX_ON,"Check to show filament segment count"));
		addParam(new ParamGui(Env.showFilLinkCt,ParamGui.CHECKBOX_ON,"Check to show filament segment linker count"));
		addParam(new ParamGui(Env.showActACt,ParamGui.CHECKBOX_ON,"Check to show ActA count"));
		addParam(new ParamGui(Env.showActABoundCt,ParamGui.CHECKBOX_ON,"Check to show filament bound ActA count"));
		addParam(new ParamGui(Env.showMonCt,ParamGui.CHECKBOX_ON,"Check to show filamentous monomer count"));
		addParam(new ParamGui(Env.showMyoCt,ParamGui.CHECKBOX_ON,"Check to show myosin motor count"));
		addParam(new ParamGui(Env.showArp23Ct,ParamGui.CHECKBOX_ON,"Check to show Arp2/3 count"));
		addParam(new ParamGui(Env.showProteinNodeCt,ParamGui.CHECKBOX_ON,"Check to show protein node count"));
		addParam(new ParamGui(Env.showMyoMiniCt,ParamGui.CHECKBOX_ON,"Check to show myosin minifilament count"));
		addParam(new ParamGui(Env.showBugDragScale,ParamGui.CHECKBOX_ON,"Check to show current bug drag scaling"));

		
		while (guiLineCt < Env.maxLayout) { addBlankHead(); }

	}
	
	public void addBlankHead() {
		InertGuiHead.addToPanel(paramPanel, new InertGuiHead("","",""));
		guiLineCt++;
	}
	
	public void addInertGuiHead(String s1, String s2, String s3) {
		InertGuiHead newHead = new InertGuiHead(s1,s2,s3);
		newHead.addToPanel(paramPanel);
		guiLineCt++;
	}
	
	public void makeApplyPanel () {
		applyPanel = new JPanel();
		applyPanel.setBackground(Env.controlBackColor);
	
		cancelB = new BareButton ("Cancel");
		cancelB.setActionCommand("Cancel");
		cancelB.setToolTipText("Cancel these changes");
		cancelB.addActionListener(this);
		cancelB.setEnabled(false);
		
		BareButton revertB = new BareButton ("Revert");
		revertB.setActionCommand("Revert");
		revertB.setToolTipText("Revert to default values");
		revertB.addActionListener(this);
		revertB.setEnabled(true);
		
		applyB = new BareButton("Apply");
		applyB.setActionCommand("Apply");
		applyB.setToolTipText("Apply Changes");
		applyB.addActionListener(this);
		applyB.setEnabled(false);
		
		applyPanel.setLayout(new FlowLayout(FlowLayout.CENTER,10,10));
		applyPanel.add(cancelB);
		applyPanel.add(revertB);
		applyPanel.add(applyB);
		
	}
	
	public void editsOn () {
		cancelB.setEnabled(true);
		applyB.setEnabled(true);
	}
	
	public void editsOff () {
		cancelB.setEnabled(false);
		applyB.setEnabled(false);
	}
}
