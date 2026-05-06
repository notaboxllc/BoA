package boxOfActin;
/*
 * InitControlFrame... vary initial conditions for the run
*/

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.*;


public class InitControl extends JFrame implements ChangeListener, ActionListener, WindowListener, ComponentListener, KeyListener, MouseListener, MouseMotionListener, AdjustmentListener {
	static BoxOfActin_Graphics parentSim;
	static JPanel titlePanel,paramPanel,applyPanel;
	static BareButton cancelB,restartB;
	static ParamGui deltaTGui,noMonsGui,noMonsRGui;
	static ParamGui [] params = new ParamGui[28];
	static int paramCt = 0;
	static int guiLineCt = 0;
	
	public InitControl (BoxOfActin_Graphics parentSim) {
		InitControl.parentSim = parentSim;
		
		makeParamPanel();
		makeApplyPanel();
		
		if (!Env.remote) {
			addKeyListener(this);
			addComponentListener(this);
			addMouseListener(this);
			addMouseMotionListener(this);
			addWindowListener(this);
			
			this.setTitle("Initial Conditions");
			initialize();
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
		
		if (act.equals("Restart")) {
			for (int i=0;i<paramCt;i++) { params[i].setValueFromGui(); }
			refreshAll();
			editsOff();
	
			BoxOfActin.setPaused();
			BoxOfActin.restartRun(false);
		}
		
		if (act.equals("Revert")) {
			revertAll();
			editsOn();
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
		JLabel titleLabel = new JLabel("Initial Conditions");
		titleLabel.setForeground(Env.controlForeColor);
		titlePanel.add(titleLabel);
	}
	
	private void addParam (ParamGui newInit) {
		params[paramCt] = newInit;
		params[paramCt].addToPanel(paramPanel);
		paramCt++;
		guiLineCt++;
	}
	
	public void makeParamPanel() {
		paramPanel = new JPanel();
		paramPanel.setBackground(Env.controlBackColor);
		paramPanel.setLayout(new GridLayout(Env.maxLayout,3));
		
		addParam(new ParamGui(Env.initialFilaments));
		addParam(new ParamGui(Env.minFilLength));
		addParam(new ParamGui(Env.maxFilLength));
		
		addBlankHead();
		addParam(new ParamGui(Env.initialMyoMiniFils));
		
		/*addBlankHead();
		addParam(new ParamGui(Env.westCircleFils));
		addParam(new ParamGui(Env.eastCircleFils));
		addParam(new ParamGui(Env.circleFilsMinLength));
		addParam(new ParamGui(Env.circleFilsMaxLength));
		addParam(new ParamGui(Env.circleFilsMixedPolarity,ParamGui.CHECKBOX_ON,"Check for mixed-polarity of filaments"));
		*/
		
		addBlankHead();
		
		addInertGuiHead(" The Protein Nodes","","");
		addParam(new ParamGui(Env.initialNodes));
		addParam(new ParamGui(Env.nodeZone));
		addParam(new ParamGui(Env.nodeRadius));
		addParam(new ParamGui(Env.numNodeMyos));
		addParam(new ParamGui(Env.numNodeMyoDimers));
		addParam(new ParamGui(Env.forminsPerNode));
		
		
		ParamGui plasTransDiffGui = new ParamGui(Env.nodeTransDiff,ParamGui.CHECKBOX_ON,"Check to manually set translation diffusivity, otherwise based on radius");
		plasTransDiffGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(plasTransDiffGui);
		
		ParamGui plasTransRotGui = new ParamGui(Env.nodeRotDiff,ParamGui.CHECKBOX_ON,"Check to manually set rotational diffusivity, otherwise based on radius");
		plasTransRotGui.setFieldType(ParamGui.EXPONENTIAL_FORMAT);
		addParam(plasTransRotGui);
		
		addParam(new ParamGui(Env.showProteinNode,ParamGui.CHECKBOX_ON,"Show Protein Node Graphics?"));
		addParam(new ParamGui(Env.collideProteinNodes,ParamGui.CHECKBOX_ON,"Do Protein Nodes Collide?"));

		
		addBlankHead();
		addInertGuiHead(" The Box","","");
		addParam(new ParamGui(Env.boxXDim));
		addParam(new ParamGui(Env.boxYDim));
		addParam(new ParamGui(Env.boxZDim));
		addParam(new ParamGui(Env.boxSpawnFraction));
		
		/*
		addBlankHead();
		addInertGuiHead(" Gliding Assay Parameters","","");
		addParam(new ParamGui(Env.fixedMyosinDensity)); 
		addParam(new ParamGui(Env.fixedMyosinZValue));
		addParam(new ParamGui(Env.glidingFilamentLength));
		addParam(new ParamGui(Env.glidingFilamentForce));
		 * 
		 */
		
		/*addBlankHead();
		addParam(new ParamGui(Env.numChamberFixedMyos));
		addParam(new ParamGui(Env.numChamberFixedMyoDimers));
		*/
		
		addBlankHead();
		ParamGui makeBugGui = new ParamGui(Env.bugShapedCrucible,ParamGui.CHECKBOX_ON,"Check to make a bug instead of a box");
		addParam(makeBugGui);
		addParam(new ParamGui(Env.bugRadius));
		addParam(new ParamGui(Env.bugLength));
		
		addBlankHead();
		addParam(new ParamGui(Env.numHotSpotsOnCortex));
		addParam(new ParamGui(Env.numOffCenterHotSpotRows));
		addParam(new ParamGui(Env.hotSpotRowSpacing));
		
		addBlankHead();
		addParam(new ParamGui(Env.membraneTransparency));
		noMonsGui = new ParamGui(Env.noMonomersSimd,ParamGui.CHECKBOX_ON,"Check to disable simulation of monomers");
		addParam(noMonsGui);
		noMonsRGui = new ParamGui(Env.noMonomersRendered,ParamGui.CHECKBOX_ON,"Check to disable rendering (but not simulation) of monomers");
		addParam(noMonsRGui);
		
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
		
		restartB = new BareButton("Restart");
		restartB.setActionCommand("Restart");
		restartB.setToolTipText("Restart Run");
		restartB.addActionListener(this);
		restartB.setEnabled(false);
		
		applyPanel.setLayout(new FlowLayout(FlowLayout.CENTER,10,10));
		applyPanel.add(cancelB);
		applyPanel.add(revertB);
		applyPanel.add(restartB);
		
	}
	
	public void editsOn () {
		cancelB.setEnabled(true);
		restartB.setEnabled(true);
	}
	
	public void editsOff () {
		cancelB.setEnabled(false);
		restartB.setEnabled(false);
	}
}
