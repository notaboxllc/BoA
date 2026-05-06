package boxOfActin;
/*
 * End2RatesControl... vary biochemical reaction rates, concentrations, etc
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

public class MiscRatesControl extends JFrame implements ChangeListener, ActionListener, WindowListener, ComponentListener, KeyListener, MouseListener, MouseMotionListener, AdjustmentListener {
	static BoxOfActin_Graphics parentSim;
	static MenuBar parentMenu;
	static JPanel titlePanel,paramPanel,applyPanel;
	static JCheckBox endsAreSameCkBox;
	static BareButton cancelB,applyB;
	static ParamGui deltaTGui;
	static ParamGui [] params = new ParamGui[30];
	static int paramCt = 0;
	static int guiLineCt = 0;
	
	public MiscRatesControl (BoxOfActin_Graphics parentSim) {
		this.parentSim = parentSim;
		
		makeRatesPanel();
		makeApplyPanel();
		
		if (!Env.remote) {
			addKeyListener(this);
			addComponentListener(this);
			addMouseListener(this);
			addMouseMotionListener(this);
			addWindowListener(this);
			
			initialize();
			this.setTitle("Rates, Concentrations, Etc");
			Dimension prefDim = getPreferredSize();
			prefDim.height += (paramCt+1)*10;
			setSize(prefDim);
			
			this.setLocation(Env.frameWidth.getIntValue()+10,0);
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
			params[i].syncToParameter();
		}
	}
	
	public void updateInfoFields () {
		for (int i=0;i<paramCt;i++) {
			if (!params[i].isInEdit()) {
				params[i].syncToParameter();
			} else {
				editsOn();
			}
		}
	}
	
	public void refreshAll () {
		for (int i=0;i<paramCt;i++) { params[i].syncToParameter(); }
	}
	
	public void revertAll () {
		for (int i=0;i<paramCt;i++) { params[i].setToDefaultValue(); }
	}

	public void actionPerformed (ActionEvent e) {
		String act = e.getActionCommand();
		
		if (act.equals("Apply")) {
			for (int i=0;i<paramCt;i++) { params[i].setValueFromGui(); }
			refreshAll();
			editsOff();
		}
		
		if (act.equals("Revert")) {
			revertAll();
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
		JLabel titleLabel = new JLabel("Rates");
		titleLabel.setForeground(Env.controlForeColor);
		titlePanel.add(titleLabel);
	}
	
	private void addParam (ParamGui newRate) {
		params[paramCt] = newRate;
		params[paramCt].addToPanel(paramPanel);
		paramCt++;
		guiLineCt++;
	}
	
	public void makeRatesPanel() {
		paramPanel = new JPanel();
		paramPanel.setBackground(Env.controlBackColor);
		paramPanel.setLayout(new GridLayout(Env.maxLayout,3));
		
		addBlankHead();
		addInertGuiHead("   Formin . . .","","");

		addParam(new ParamGui(Env.forminRelease));
		
		addBlankHead();
		addInertGuiHead("   Capping . . .","","");

		addParam(new ParamGui(Env.capRate));
		
		addBlankHead();
		addInertGuiHead("   Cofilin . . .","","");

		addParam(new ParamGui(Env.cofilinRate));
		addParam(new ParamGui(Env.cofilinRatio));
		addParam(new ParamGui(Env.bundleStableFactor,ParamGui.CHECKBOX_ON,"Check if bundles resist cofilin"));
		
		addBlankHead();
		addInertGuiHead("   Tropomyosin . . .","","");
		
		addParam(new ParamGui(Env.tropoOnRate));
		addParam(new ParamGui(Env.tropoOffRate));
		
		addBlankHead();
		addInertGuiHead("   Arp2/3 / Branching . . .","","");
		addParam(new ParamGui(Env.branchRateNearArpFactors));
		addParam(new ParamGui(Env.branchZone));
		addParam(new ParamGui(Env.nucRateNearArpFactors));
		addParam(new ParamGui(Env.rhoHotLifetime));
		
		addBlankHead();
		addInertGuiHead("   Conglomerates Lifetimes . . .","","");
		addParam(new ParamGui(Env.nodeLifetime));
		addParam(new ParamGui(Env.myoMiniLifetime));

	
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
	
	public void addInertGuiHead(InertGuiHead newHead) {
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
