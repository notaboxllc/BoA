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

public class ActAControl extends JFrame implements ChangeListener, ActionListener, WindowListener, ComponentListener, KeyListener, MouseListener, MouseMotionListener, AdjustmentListener {
	static BoxOfActin_Graphics parentSim;
	static JPanel titlePanel,paramPanel,applyPanel;
	static BareButton cancelB,applyB;
	static ParamGui fWidthGui, fHeightGui;
	static ParamGui [] inits = new ParamGui[22];
	static int paramCt = 0;
	static int guiLineCt = 0;
	
	public ActAControl (BoxOfActin_Graphics parentSim) {
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
			this.setTitle("ActA");
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
		
		addInertGuiHead(" ActA Distribution","","");
		addParam(new ParamGui(Env.totalActACt));
		addParam(new ParamGui(Env.ultrapolarActA,ParamGui.CHECKBOX_ON,"Check to use ultrapolar ActA distribution"));

		addBlankHead();
		addInertGuiHead(" Forces/Binding","","");
		addParam(new ParamGui(Env.actAMaxTetherStrain));
		
		ParamGui actASpringGui = new ParamGui(Env.actASpringK,ParamGui.CHECKBOX_ON,"Check to use simple spring for ActA-Filament tether");
		actASpringGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(actASpringGui);
		
		addParam(new ParamGui(Env.actATetherTransAttn));
		addParam(new ParamGui(Env.actATetherRotAttn));
		
		ParamGui fracMoveGui = new ParamGui(Env.actATetherFracMove);
		fracMoveGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(fracMoveGui);
		//addParam(new ParamGui(Env.actATetherFracMove));
		addBlankHead();
		addParam(new ParamGui(Env.actADetachProb));
		addParam(new ParamGui(Env.closeActATolerance));
		addParam(new ParamGui(Env.actAUncapDistance));

		
		addBlankHead();
		addInertGuiHead(" Biochem.","","");
		addParam(new ParamGui(Env.actANucProb));
		addParam(new ParamGui(Env.actABranchProb));
		
		addBlankHead();
		addInertGuiHead(" With Collision","","");
		addParam(new ParamGui(Env.checkActABindingProb));
		addParam(new ParamGui(Env.contactUncapsProb));
		
	
		
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
