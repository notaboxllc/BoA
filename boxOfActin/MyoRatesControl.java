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

public class MyoRatesControl extends JFrame implements ChangeListener, ActionListener, WindowListener, ComponentListener, KeyListener, MouseListener, MouseMotionListener, AdjustmentListener {
	static BoxOfActin_Graphics parentSim;
	static MenuBar parentMenu;
	static JPanel titlePanel,paramPanel,applyPanel;
	static JCheckBox endsAreSameCkBox;
	static BareButton cancelB,applyB;
	static ParamGui deltaTGui;
	static ParamGui [] params = new ParamGui[30];
	static int paramCt = 0;
	static int guiLineCt = 0;
	
	public MyoRatesControl (BoxOfActin_Graphics parentSim) {
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
		addInertGuiHead("   Myosin Geometry . . .","","");
		addParam(new ParamGui(Env.myoMotorLength));
		addParam(new ParamGui(Env.myoLeverLength));
		addParam(new ParamGui(Env.myoRodLength));
		
		addBlankHead();
		addInertGuiHead("   Myosin Biochemical Transistions . . .","","");

		ParamGui atpOnMyoGui = new ParamGui(Env.atpOnMyo);
		atpOnMyoGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(atpOnMyoGui);
		
		addBlankHead();
		addInertGuiHead("","On Filament","");
		ParamGui mOnATP_ADPPiGui = new ParamGui(Env.myoOnFilATP_ADPPi);
		mOnATP_ADPPiGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(mOnATP_ADPPiGui);
		
		ParamGui mOnADPPi_ADPGui = new ParamGui(Env.myoOnFilADPPi_ADP);
		mOnADPPi_ADPGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(mOnADPPi_ADPGui);
		
		ParamGui mOnADP_NONEGui = new ParamGui(Env.myoOnFilADP_None);
		mOnADP_NONEGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(mOnADP_NONEGui);
		
		addBlankHead();
		addInertGuiHead("","Off Filament","");
		ParamGui mOffATP_ADPPiGui = new ParamGui(Env.myoOffFilATP_ADPPi);
		mOffATP_ADPPiGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(mOffATP_ADPPiGui);
		
		ParamGui mOffADPPi_ADPGui = new ParamGui(Env.myoOffFilADPPi_ADP);
		mOffADPPi_ADPGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(mOffADPPi_ADPGui);
		
		ParamGui mOffADP_NONEGui = new ParamGui(Env.myoOffFilADP_None);
		mOffADP_NONEGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(mOffADP_NONEGui);
		
		
		addBlankHead();
		addInertGuiHead("   Myosin Force Related. . .","","");
		addParam(new ParamGui(Env.myosinStallForce));
		addParam(new ParamGui(Env.myosinBreakForce)); 
		addParam(new ParamGui(Env.alphaCatch));
		addParam(new ParamGui(Env.alphaSlip));
		ParamGui xCatchGui = new ParamGui(Env.xCatch);
		xCatchGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(xCatchGui);
		ParamGui xSlipGui = new ParamGui(Env.xSlip);
		xSlipGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(xSlipGui);
		addParam(new ParamGui(Env.kOff));
		addParam(new ParamGui(Env.myoBrownianAttn));
		
		
		addBlankHead();
		addParam(new ParamGui(Env.myoColTol));
		ParamGui rebindGui = new ParamGui(Env.myoRebindTime);
		rebindGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(rebindGui);
		
		
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
