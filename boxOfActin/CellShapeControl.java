package boxOfActin;
/*
 * Cell Shape Related... membrane, etc
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

public class CellShapeControl extends JFrame implements ChangeListener, ActionListener, WindowListener, ComponentListener, KeyListener, MouseListener, MouseMotionListener, AdjustmentListener {
	static BoxOfActin_Graphics parentSim;
	static MenuBar parentMenu;
	static JPanel titlePanel,paramPanel,applyPanel;
	static JCheckBox endsAreSameCkBox;
	static BareButton cancelB,applyB;
	static ParamGui deltaTGui;
	static ParamGui [] params = new ParamGui[30];
	static int paramCt = 0;
	static int guiLineCt = 0;
	
	public CellShapeControl (BoxOfActin_Graphics parentSim) {
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
			this.setTitle("Cell Shape");
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
		addInertGuiHead("   Membrane . . .","","");
		
		addParam(new ParamGui(Env.membraneCellRadius));
		addParam(new ParamGui(Env.membraneCellPackingFactor));
		addParam(new ParamGui(Env.membraneNodeRadius));
		addParam(new ParamGui(Env.membraneXNodeCt));
		addParam(new ParamGui(Env.membraneYNodeCt));
		ParamGui dragScale = new ParamGui(Env.membraneNodeDragScale);
		dragScale.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(dragScale);
		ParamGui brownianScale = new ParamGui(Env.membraneBrownianScale);
		brownianScale.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(brownianScale);
		addParam(new ParamGui(Env.membraneMaxLinkStrain));
		addParam(new ParamGui(Env.maxMembranePasses));
		ParamGui outwardForce = new ParamGui(Env.outwardCellForce);
		outwardForce.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(outwardForce);
		
		addBlankHead();
		addParam(new ParamGui(Env.membraneTstBallRadius));
		addParam(new ParamGui(Env.nodeGrowthPerStep));
		ParamGui ballForce = new ParamGui(Env.membraneTstBallForce);
		ballForce.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(ballForce);
		addParam(new ParamGui(Env.filTipRadiusForCollisions));
		
		addBlankHead();
		addInertGuiHead("   Fill Nodes . . .","","");
		addParam(new ParamGui(Env.fillNodeRadius));
		addParam(new ParamGui(Env.fillNodeCt));
		ParamGui fillNodeDragScale = new ParamGui(Env.fillNodeDragScale);
		fillNodeDragScale.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(fillNodeDragScale);
		ParamGui fillNodeBrownianScale = new ParamGui(Env.fillNodeBrownianScale);
		fillNodeBrownianScale.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(fillNodeBrownianScale);
	
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


