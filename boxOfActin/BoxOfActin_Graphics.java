package boxOfActin;

import java.awt.*;
import java.awt.event.*;
import java.applet.*;

import javax.media.j3d.*;
import javax.swing.JFrame;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.vecmath.*;

import java.io.File;
import java.text.DecimalFormat;

import com.sun.j3d.utils.universe.*;


public class BoxOfActin_Graphics extends JFrame
	implements WindowListener, CheckboxMenuListener, ActionListener, ItemListener, ComponentListener {

	//  Should the scene be compiled?
	private boolean shouldCompile = true;

	//  GUI objects for our subclasses
	protected static Frame    theFrame    = null;
	protected static MenuBar  theMenuBar  = null;
	protected static CapturingCanvas3D theCanvas = null;
	protected static JPanel topPanel = null;
	protected static JLabel timeLabel = null;
	protected static JLabel concLabel = null;
	protected static JLabel filCtLabel = null;
	protected static JLabel monCtLabel = null;
	protected static JLabel myoCtLabel = null;
	protected TransformGroup theViewTransform = null;
	protected TransformGroup theSceneTransform = null;
	
	//MouseListener lockMouse;				// handle on MouseListener for locking view changes (nice during extended runs with gui)
	//MouseListener [] myMouseListeners;
	
	public static int frameHeadSize;		// different on different systems... size of bar at top of frame and the diff between canvas3d and JFrame height

	//  Private GUI objects and state
	public DirectionalLight headlight = null;
	private ExamineViewerBehavior examineBehavior = null;

	private Background background = null;
	private Switch shadingSwitch = null;
	private static Group scene = null;
	public static BranchGroup bugState = null;
	
	
	// for menus
	private static boolean headlightOnOff = true;
	private static CheckboxMenuItem headlightMenuItem = null;
	private CheckboxMenuItem paintOnMenuItem = null;
	private static CheckboxMenuItem info2DMenuItem = null;
	private static CheckboxMenuItem info3DMenuItem = null;
	private static CheckboxMenuItem writeFileMenuItem = null;
	private static CheckboxMenuItem writeQKFileMenuItem = null;
	private static CheckboxMenuItem writeLOGFileMenuItem = null;
	private static CheckboxMenuItem renderQKFileMenuItem = null;
	public static CheckboxMenuItem pausedMenuItem = null;
	public static CheckboxMenuItem runMenuItem = null;
	private static CheckboxMenuItem rotateMenuItem = null;
	private static CheckboxMenuItem lockViewMenuItem = null;
	private static CheckboxMenuItem helixFilsMenuItem = null;
	private static CheckboxMenuItem showXLinksMenuItem = null;
	private static CheckboxMenuItem showActAsMenuItem = null;
	private static CheckboxMenuItem sphereFilsMenuItem = null;
	private static CheckboxMenuItem polarityArrowsMenuItem = null;
	private static CheckboxMenuItem filMotionOffMenuItem = null;
	private static CheckboxMenuItem bugMotionOffMenuItem = null;
	private static CheckboxMenuItem myosOffMenuItem = null;
	private static CheckboxMenuItem myoMotionOffMenuItem = null;
	private static CheckboxMenuItem myoMiniFilMotionOffMenuItem = null;
	private static CheckboxMenuItem plasmidMotionOffMenuItem = null;
	private static CheckboxMenuItem twoNodesOneFilMenuItem = null;
	private static CheckboxMenuItem actinWithMyoMinisMenuItem = null;
	private static CheckboxMenuItem twoByTwoNodesMenuItem = null;
	private static CheckboxMenuItem threeByThreeNodesMenuItem = null;
	private static CheckboxMenuItem nodeChainMenuItem = null;
	private static CheckboxMenuItem compressionCritMenuItem = null;
	private static CheckboxMenuItem orderedCenteredMenuItem = null;
	private static CheckboxMenuItem filCoordSysOnMenuItem = null;
	private static CheckboxMenuItem fixedMyoClustersMenuItem = null;
	private static CheckboxMenuItem glidingAssayMenuItem = null;
	private static CheckboxMenuItem xLinkTestMenuItem = null;
	private static CheckboxMenuItem nodeLinkTestMenuItem = null;
	private static CheckboxMenuItem releaseNodeMyosMenuItem = null;
	private static CheckboxMenuItem randomNodesOnMenuItem = null;



	private int currentAppearance = 0;
	private CheckboxMenuItem[] appearanceMenu;
	
	// report strings
	static String filCtString;
	static String filSegCtString;
	
	
	// control frames with organizing tab and panel
	public static JFrame controlFrame = new JFrame("Control Panel");
	public static JPanel controlPanel = new JPanel(new GridLayout(1,1));
	public static JTabbedPane controlTab = new JTabbedPane();
	public static InitControl initControl;
	public static GraphicsControl graphicsControl;
	public static RenderControl renderControl;
	public static EnvControl envControl;
	public static MechControl mechControl;
	public static XLinkControl xLinkControl;
	public static ActAControl actAControl;
	public static EndRatesControl endRatesControl;
	public static MiscRatesControl miscRatesControl;
	public static MyoRatesControl myoRatesControl;
	public static CellShapeControl cellShapeControl;
	
	
	// text formats
	static DecimalFormat timeFormat = new DecimalFormat ("00.0000");
	static DecimalFormat concFormat = new DecimalFormat ("0.0000");
	static DecimalFormat deflectionFormat = new DecimalFormat ("0.0000");
	static DecimalFormat expFormat = new DecimalFormat ("0.000E0");
	
	public BoxOfActin_Graphics( ) {
		
		if (!Env.remote) { 
			makeAllControls(this); 
			//new infoCCD.Info(false,true,true);
		}
		
	}
	
	public static void makeAllControls(BoxOfActin_Graphics pG) {
		initControl = new InitControl(pG);
		envControl = new EnvControl(pG);
		endRatesControl = new EndRatesControl(pG);
		miscRatesControl = new MiscRatesControl(pG);
		mechControl = new MechControl(pG);
		graphicsControl = new GraphicsControl(pG);
		xLinkControl = new XLinkControl(pG);
		actAControl = new ActAControl(pG);
		myoRatesControl = new MyoRatesControl(pG);
		cellShapeControl = new CellShapeControl(pG);

		
		controlTab.addTab("init",initControl.getContentPane());
		controlTab.addTab("env",envControl.getContentPane());
		controlTab.addTab("cell", cellShapeControl.getContentPane());
		controlTab.addTab("endRates",endRatesControl.getContentPane());
		controlTab.addTab("miscRates",miscRatesControl.getContentPane());
		controlTab.addTab("myoRates", myoRatesControl.getContentPane());
		controlTab.addTab("mech",mechControl.getContentPane());
		controlTab.addTab("xLink", xLinkControl.getContentPane());
		controlTab.addTab("actA", actAControl.getContentPane());
		controlTab.addTab("graphics",graphicsControl.getContentPane());
		
		controlPanel.add(controlTab);
		controlFrame.getContentPane().add(controlPanel);
		// set location and size of controlFrame
		controlFrame.setSize(controlFrame.getPreferredSize());
		controlFrame.setLocationRelativeTo(null);
		//controlFrame.setLocation(Env.frameWidth.getIntValue()+10,0);
		
		renderControl = new RenderControl(pG);	// not part of param control set above
	}
	
	public static void updateAllControls() {
		initControl.updateInfoFields();
		graphicsControl.updateInfoFields();
		envControl.updateInfoFields();
		mechControl.updateInfoFields();
		xLinkControl.updateInfoFields();
		endRatesControl.updateInfoFields();
		miscRatesControl.updateInfoFields();
		myoRatesControl.updateInfoFields();
		cellShapeControl.updateInfoFields();
		actAControl.updateInfoFields();
	}


	 //  Initializes the application when invoked as an applet.
	public void init( ) {
		this.initialize();
		this.buildUniverse( );
		this.showFrame( );
	}


	/**
	 *  Initializes the Example by parsing command-line arguments,
	 *  building an AWT Frame, constructing a menubar, and creating
	 *  the 3D canvas.
	 *
	 *  @param   args    a String array of command-line arguments
	 */
	protected void initialize() {

		// Build the frame
		theFrame = new Frame( );
		theFrame.setBackground(Env.universeColor);
		theFrame.setSize( Env.frameWidth.getIntValue(), Env.frameHeight.getIntValue() );
		theFrame.setTitle( "Boxes of Actin!" );
		theFrame.setLayout( new BorderLayout( ) );

		// Set up a close behavior
		theFrame.addComponentListener(this);
		theFrame.addWindowListener( this );
		
		// Create a canvas
		theCanvas = new CapturingCanvas3D(SimpleUniverse.getPreferredConfiguration());
		theCanvas.setSize( Env.frameWidth.getIntValue(), Env.frameHeight.getIntValue() );
		theFrame.add( "Center", theCanvas );

		// Get Mouselistener to lock if desired
		//myMouseListeners = theFrame.getMouseListeners();
		//lockMouse = myMouseListeners[0];
				
		// Create time panel
		//makeTopPanel();
		//theFrame.add( "North", topPanel );
		
		// Pack
		theFrame.pack( );
		theFrame.validate( );
		//		exampleFrame.setVisible( true );
		
		makeMenuBar();
		
		// set frameHeadSize for future canvas/jframe resizing
		frameHeadSize = theFrame.getHeight() - theCanvas.getHeight();
		//talkln ("frameHeadSize: " + frameHeadSize);
	}


	//--------------------------------------------------------------
	//  SCENE CONTENT
	//--------------------------------------------------------------

	/**
	 *  Builds the 3D universe by constructing a virtual universe
	 *  (via SimpleUniverse), a view platform (via SimpleUniverse), and
	 *  a view (via SimpleUniverse).  A headlight is added and a
	 *  set of behaviors initialized to handle navigation types.
	 */
	protected void buildUniverse( ) {
		SimpleUniverse universe = new SimpleUniverse(theCanvas);
		
		Viewer viewer = universe.getViewer( );
		ViewingPlatform viewingPlatform = universe.getViewingPlatform( );
		theViewTransform = viewingPlatform.getViewPlatformTransform( );


		//
		//  Create a "headlight" as a forward-facing directional light.
		//  Set the light's bounds to huge.  Since we want the light
		//  on the viewer's "head", we need the light within the
		//  TransformGroup containing the ViewPlatform.  The
		//  ViewingPlatform class creates a handy hook to do this
		//  called "platform geometry".  The PlatformGeometry class is
		//  subclassed off of BranchGroup, and is intended to contain
		//  a description of the 3D platform itself... PLUS a headlight!
		//  So, to add the headlight, create a new PlatformGeometry group,
		//  add the light to it, then add that platform geometry to the
		//  ViewingPlatform.
		//
		BoundingSphere allBounds = new BoundingSphere(
			new Point3d( 0.0, 0.0, 0.0 ), 10000.0 );

		PlatformGeometry pg = new PlatformGeometry( );
		headlight = new DirectionalLight( );
		headlight.setColor( Env.headlightColor );
		headlight.setDirection( new Vector3f( 0.0f, 0.0f, -1.0f ) );
		headlight.setInfluencingBounds( allBounds );
		headlight.setCapability( Light.ALLOW_STATE_WRITE );
		pg.addChild( headlight );
		viewingPlatform.setPlatformGeometry( pg );


		//
		//  Create the 3D content BranchGroup, containing:
		//
		//    - a TransformGroup who's transform the examine behavior
		//      will change (when enabled).
		//
		//    - 3D geometry to view
		//
		// Build the scene root
		BranchGroup sceneRoot = new BranchGroup( );

		// Build a transform that we can modify
		theSceneTransform = new TransformGroup( );
		theSceneTransform.setCapability(TransformGroup.ALLOW_TRANSFORM_READ );
		theSceneTransform.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE );
		theSceneTransform.setCapability(Group.ALLOW_CHILDREN_EXTEND );


		//
		//  Build the scene, add it to the transform, and add
		//  the transform to the scene root
		//
		Group scene = this.buildScene( );
		theSceneTransform.addChild( scene );
		sceneRoot.addChild( theSceneTransform );


		examineBehavior = new ExamineViewerBehavior(theSceneTransform, theFrame ); 
		examineBehavior.setSchedulingBounds( allBounds );
		sceneRoot.addChild( examineBehavior );

		examineBehavior.setEnable( true );


		//
		//  Compile the scene branch group and add it to the SimpleUniverse.
		//
		if ( shouldCompile ) { sceneRoot.compile( ); }
		universe.addBranchGraph( sceneRoot );
		resetView();
	}
	
	public boolean canvasSizeCurrent () {
		if (theCanvas.getHeight() != Env.frameHeight.getIntValue()) { return false; }
		if (theCanvas.getWidth() != Env.frameWidth.getIntValue()) { return false; }
		return true;
	}
	
	public void makeCanvasSizeCurrent () {
		setCanvasSize (Env.frameWidth.getIntValue(), Env.frameHeight.getIntValue());
	}
	
	public void updateFrameSizeParams () {
		Env.frameWidth.setValue(theFrame.getWidth());
		Env.frameHeight.setValue(theFrame.getHeight()-frameHeadSize);
	}
	
	public void updateCanvasSizeParams() {
		Env.frameWidth.setValue(theFrame.getWidth());
		Env.frameHeight.setValue(theFrame.getHeight()-frameHeadSize);
	}

	public void setCanvasSize (int width, int height) {
		//talkln ("Resetting canvas size to " + width + "," + height);
		theFrame.remove(theCanvas);
		theFrame.setSize(width,height+frameHeadSize);
		theCanvas.setSize(width,height);
		theFrame.add("Center",theCanvas);
		
		//topPanel.setSize(width,height);
		//topPanel.remove(timeLabel);
		//topPanel.add(timeLabel);
		//topPanel.add(concLabel);
	}
	
	//  Reset transforms
	public void resetView( )
	{  
		Transform3D trans = new Transform3D( );
		trans.setScale(Env.transScale.getValue());
		theSceneTransform.setTransform( trans );
		trans.set( new Vector3f( 0.0f, 0.0f, 30.0f ) );
		theViewTransform.setTransform( trans );
	}
	
	public void setRotZ90View( )
	{
		Transform3D trans = new Transform3D( );
		Transform3D rotTrans = new Transform3D();
		rotTrans.rotZ(Math.PI/2);
		trans.mul(rotTrans);
		trans.setScale(Env.transScale.getValue());
		theSceneTransform.setTransform( trans );
		trans.set( new Vector3f( 0.0f, 0.0f, 30.0f ) );
		theViewTransform.setTransform( trans );
	}
	
	public void setRotY90View( )
	{
		Transform3D trans = new Transform3D( );
		Transform3D rotTrans = new Transform3D();
		rotTrans.rotY(Math.PI/2);
		trans.mul(rotTrans);
		trans.setScale(Env.transScale.getValue());
		theSceneTransform.setTransform( trans );
		trans.set( new Vector3f( 0.0f, 0.0f, 30.0f ) );
		theViewTransform.setTransform( trans );
	}
	
	public void lockView () {
		talkln("Want to lock view but code not written yet");
		if (Env.lockView.isActive()) { 
			//theFrame.removeMouseListener(lockMouse);
		} else {
			//theFrame.addMouseListener(lockMouse);
		}
	}
	
	public void reportView() {
		Transform3D curT3D = new Transform3D();
		theSceneTransform.getTransform(curT3D);
		System.out.println("Current transform3D = " + curT3D.toString());
	}
	
	public void setFavoriteView () {
		Matrix4d mat4d = new Matrix4d(0.929592463305119, -0.34387011106253973, 0.1327071922849392, -0.5600000000000003, 0.17605656110028217, 0.7305565250518409, 0.6597660577793522, 0.8800000000000003, -0.32382393280895394, -0.5899495829492535, 0.7396604289254122, 14.199999999999994, 0.0, 0.0, 0.0, 1.0);
		Transform3D favT3D = new Transform3D(mat4d);
		theSceneTransform.setTransform(favT3D);
	}
	
	public void setView (String viewFileStr) {
		Transform3D favT3D = FileOps.readView(viewFileStr);
		if (favT3D != null) {
			theSceneTransform.setTransform(favT3D);
		} else { talkln ("Error setting view"); }
	}
	
	public void saveAsView () { 
		Transform3D favT3D = new Transform3D();
		theSceneTransform.getTransform(favT3D);
		FileOps.saveView(favT3D);
	}
	
	public void rotateViewY() {
		double rotAng = 1*Math.PI/180;
		Transform3D rotT3D = new Transform3D();
		rotT3D.rotY(rotAng);
		Transform3D curT3D = new Transform3D();
		theSceneTransform.getTransform(curT3D);
		curT3D.mul(rotT3D);
		curT3D.setScale(Env.transScale.getValue());
		theSceneTransform.setTransform(curT3D);
	}
	
	public void rotateViewY(double rotAng) {
		rotAng = rotAng*Math.PI/180;
		Transform3D rotT3D = new Transform3D();
		rotT3D.rotY(rotAng);
		Transform3D curT3D = new Transform3D();
		theSceneTransform.getTransform(curT3D);
		curT3D.mul(rotT3D);
		curT3D.setScale(Env.transScale.getValue());
		theSceneTransform.setTransform(curT3D);
	}
	
	public void rotateViewZ(double rotAng) {
		rotAng = rotAng*Math.PI/180;
		Transform3D rotT3D = new Transform3D();
		rotT3D.rotZ(rotAng);
		Transform3D curT3D = new Transform3D();
		theSceneTransform.getTransform(curT3D);
		curT3D.mul(rotT3D);
		curT3D.setScale(Env.transScale.getValue());
		theSceneTransform.setTransform(curT3D);
	}


	private BranchGroup initialBugScene() {
		bugState = new BranchGroup();
		allBranchGroupCapabilities(bugState);
		
		for (int i=0;i<Thing.thingCt;i++) {
			bugState.addChild(Thing.theThings[i].getGraphicsNode());
		}
		return bugState;
	}
	
	public static void updateBugScene() {
		Thing curThing;
		FilLink curFL;
		NodeLink curNL;
		Arp23 curArp;
		Myosin curMyo;
		ActA curActA;
		//if (bugState != null) { bugState.detach();}
		//bugState = new BranchGroup();
		//allBranchGroupCapabilities(bugState);

		for (int i=0;i<Thing.thingCt;i++) {
			curThing = Thing.theThings[i];
			if (!curThing.removeMe) {
				if (!curThing.graphicsMade) {
					bugState.addChild(curThing.getGraphicsNode());
				} else {
					curThing.updateGraphics();
				}
			}
		}
		
		if (Env.showXLinks.isActive()) {
			for (int i=0;i<FilLink.filLinkCt;i++) {
				if (FilLink.filLinks[i] == null) { break; }	// protect from null pointer exception when there are no FilLinks
				curFL = FilLink.filLinks[i];
				if (!curFL.graphicsMade) {
					bugState.addChild(curFL.getGraphicsNode());
				} else {
					curFL.updateGraphics();
				}
			}
		}
		
		if (Env.showXLinks.isActive()) {  // NodeLinks
			for (int i=0;i<NodeLink.nodeLinkCt;i++) {
				if (NodeLink.nodeLinks[i] == null) { break; }	// protect from null pointer exception when there are no FilLinks
				curNL = NodeLink.nodeLinks[i];
				if (!curNL.graphicsMade) {
					bugState.addChild(curNL.getGraphicsNode());
				} else {
					curNL.updateGraphics();
				}
			}
		}
		
		if (Env.showActAs.isActive()) { // ActA
			for (int i=0;i<ActA.actACt;i++) {
				if (ActA.theActAs[i] == null) { break; }	// protect from null pointer exception when there are no FilLinks
				curActA = ActA.theActAs[i];
				if (!curActA.graphicsMade) {
					bugState.addChild(curActA.getGraphicsNode());
				} else {
					curActA.updateGraphics();
				}
			}
		}
		
		if (false) { //(Env.showXLinks) {
			for (int i=0;i<Arp23.arp23Ct;i++) {
				if (Arp23.theArp23s[i] == null) { break; }	// protect from null pointer exception when there are no FilLinks
				curArp = Arp23.theArp23s[i];
				if (!curArp.graphicsMade) {
					bugState.addChild(curArp.getGraphicsNode());
				} else {
					curArp.updateGraphics();
				}
			}
		}
		
		
		//scene.addChild(bugState);
	}
	
	public static void updateQKBugScene() {
		Thing curThing;
		FilLink curFL;
		Myosin curMyo;
		Monomer curMon;
		FilSegment curFilSeg;
		
		for (int i=0;i<Thing.thingCt;i++) {
			curThing = Thing.theThings[i];
			if (Env.filRenderOff && (curThing instanceof FilSegment)) {
				// do nothing
			} else {
				if (!curThing.graphicsMade) {
					bugState.addChild(curThing.getGraphicsNode());
				} else {
					curThing.updateGraphics();
				}
			}
		}
		
		for (int i=FilSegment.filSegRenderCt;i<FilSegment.filSegmentCt;i++) {
			FilSegment.theFilSegments[i].detachGraphics();
		}
		
		if (Env.showXLinks.isActive()) {
			for (int i=0;i<FilLink.filLinkCt;i++) {
				curFL = FilLink.filLinks[i];
				if (!curFL.graphicsMade) {
					bugState.addChild(curFL.getGraphicsNode());
				} else {
					curFL.updateGraphics();
				}
			}
		}
		
		
		if (!Env.noMonomersRendered.isActive() && !Env.filRenderOff) {

			for (int x=Monomer.monRenderCt;x<Monomer.monCt;x++) {
				Monomer.theMonomers[x].detachGraphics();
				//Monomer.theMonomers[x].setToFarAway();
			}
			
			for (int m=0;m<Monomer.monRenderCt;m++) {
				curMon = Monomer.theMonomers[m];
				//talkln ("curMon number " + String.valueOf(m) + " is " + String.valueOf(curMon) + " and is in state " + String.valueOf(curMon.getState()));
				if (!curMon.graphicsIn) {
					curMon.addGraphics(bugState);
				}
			}

		} 
	}
	
	
	public static void clearForRun () {
		synchronized(Env.safeO) {
			Monomer.removeAll();	// removes monomers from rendered files
			FilSegment.removeAll();
			ProteinNode.removeAll();
			Thing.removeDeadThings();
			Thing.thingCt = 0;	// no Things left
			if (!Env.remote) { Thing.theBox.G.detach(); }
			Thing.theBox = null;
			if (!Env.remote) {
				//updateBugScene();
				updateAllControls();
			}
			Env.simulationTime = 0;
		}
	}
	
	public static void clearForRender () {
		synchronized(Env.safeO) {
			FilSegment.removeAll();
			ProteinNode.removeAll();
			Thing.removeDeadThings();
			Thing.thingCt = 0;	// no Things left
			Thing.theBox.G.detach();
			Thing.theBox = null;
		}
	}
	
	public static void captureImage (CapturingCanvas3D theCanvas) {
		theCanvas.writePNG_ = true;
		theCanvas.repaint();
	}
	
	public static void waitOnCapture (CapturingCanvas3D theCanvas) {
		while (theCanvas.isRendering()) {
			try { Thread.sleep(4000); } catch (InterruptedException e) { talkln ("error sleeping"); }
		}
	}
	
	//
	//  Build scene
	//
	public Group buildScene( )
	{
		
		// Build the scene root
		scene = new Group( );
		scene.setCapability(scene.ALLOW_CHILDREN_WRITE);
		scene.setCapability(scene.ALLOW_CHILDREN_EXTEND);

		// Create application bounds
		BoundingSphere worldBounds = new BoundingSphere(new Point3d( 0.0, 0.0, 0.0 ),1000.0 );                      // Extent

		// Set the background color and its application bounds
		background = new Background( );
		background.setColor(Env.universeColor3f);
		background.setCapability( Background.ALLOW_IMAGE_WRITE );
		background.setApplicationBounds( worldBounds );
		scene.addChild( background );

		// Build foreground geometry
		scene.addChild( initialBugScene( ) );

		return scene;
	}


	public void makeTopPanel () {  // this method is currently unused?
		topPanel = new JPanel();
		topPanel.setSize(Env.frameWidth.getIntValue(),8);
		topPanel.setLayout(new GridLayout(1,5));
		topPanel.setBackground(Env.controlBackColor);
		
		timeLabel = new JLabel(" ");
		timeLabel.setForeground(Env.controlForeColor);
		topPanel.add(timeLabel);
		
		concLabel = new JLabel(" ");
		concLabel.setForeground(Env.controlForeColor);
		topPanel.add(concLabel);
		
		filCtLabel = new JLabel(" ");
		filCtLabel.setForeground(Env.controlForeColor);
		topPanel.add(filCtLabel);
		
		monCtLabel = new JLabel(" ");
		monCtLabel.setForeground(Env.controlForeColor);
		topPanel.add(monCtLabel);
		
		myoCtLabel = new JLabel(" ");
		myoCtLabel.setForeground(Env.controlForeColor);
		topPanel.add(myoCtLabel);
	}
	
	public static void setTopLabels () {
		setTimeLabel(); 
		setConcLabel();
		setFilCtLabel();
		setMonCtLabel();
		setMyoCtLabel();
	}
	
	public static String getTimeString() {
		return new String (" Time: " + String.valueOf(timeFormat.format(Env.simulationTime)) + " s ");
	}
	
	public static void setTimeLabel () {
		timeLabel.setText(getTimeString());
	}
	
	public static String getConcString() {
		return new String (" Actin: " + String.valueOf(concFormat.format(Env.actinConc.getValue())) + " µM "); 
	}
	
	public static String getNonHydroConcString() {
		return new String (" Non-hydro Actin: " + String.valueOf(concFormat.format(Env.actinConcNonHydro.getValue())) + " µM "); 
	}
	
	public static String getMyoCtString() {
		return new String (" Myosins: " + String.valueOf(Myosin.myoCt));
	}
	
	public static void setConcLabel () {
		concLabel.setText(getConcString()); 
	}
	
	public static void setMyoCtLabel () {
		myoCtLabel.setText(getMyoCtString()); 
	}
	
	public static String getFilLinkCtString() {
		int filLinkCt = 0;
		if (Env.fromQKFile) { filLinkCt = FilLink.filLinkRenderCt; } else { filLinkCt = FilLink.filLinkCt - FilLink.filLinkCt_inactive; }
		return new String (" XLinks#: " + String.valueOf(filLinkCt)); 
	}
	
	public static String getActACtString () {
		return new String (" ActA#: " + String.valueOf(ActA.actACt));
	}
	
	public static String getActABoundCtString () {
		return new String (" ActABound#: " + String.valueOf(ActA.countBoundActAs()));
	}
	
	public static String getFilSegCtString() {
		int filCt;
		if (Env.fromQKFile) {
			filCt = FilSegment.filamentRenderCt;
		} else {
			filCt = FilSegment.filSegmentCt;
		}
		return new String (" FilSegs#: " + String.valueOf(filCt)); 
	}
	
	public static String getFilCtString() {
		int filCt;
		if (Env.fromQKFile) {
			filCt = FilSegment.filamentRenderCt;
		} else {
			filCt = FilSegment.getNumberOfFilaments();
		}
		filCtString = " Filament#: " + String.valueOf(filCt);
		return filCtString; 
	}
	
	public static void setFilCtLabel () {
		filCtLabel.setText(getFilCtString());
	}
	
	public static String getMonCtString() {
		int monCt;
		if (Env.fromQKFile) {
			monCt = Monomer.monRenderCt;
		} else {
			monCt = FilSegment.monomerSum();
		}
		return new String (" Mon#: " + String.valueOf(monCt)); 
	}
	
	public static String getArp23CtString() {
		return new String (" Arp2/3: " + String.valueOf(Arp23.getNumberActiveArps()));
	}
	
	public static String getNodeCtString() {
		return new String (" Nodes: " + String.valueOf(ProteinNode.nodeCt));
	}
	
	public static String getMyoMiniCtString() {
		return new String (" MyoMinis: " + String.valueOf(MyoMiniFilament.myoMiniFilCt));
	}
	
	public static String getBugDragString() {
		if (Env.useAveBugDragScale.isActive()) {
			return new String (" BugDrag: " + String.format("%.2f",Bug.bugDragAve.averageVal()));
		} else {
			return new String (" BugDrag: " + String.format("%.2f",Bug.bugDragScale));
		}
	}
	
	public static void setMonCtLabel () {
		monCtLabel.setText(getMonCtString());
	}
	
	public void makeMenuBar () {
		theMenuBar = new MenuBar();
		theFrame.setMenuBar( theMenuBar );

		// Add menu for program control
		Menu m = new Menu ("Program");
		m.addActionListener(this);
		
		runMenuItem = new CheckboxMenuItem ("Run",!Env.paused);
		runMenuItem.addItemListener(this);
		m.add(runMenuItem);
		
		pausedMenuItem = new CheckboxMenuItem ("Pause",Env.paused);
		pausedMenuItem.addItemListener(this);
		m.add(pausedMenuItem);
		
		m.add("Restart");
		
		m.add("--------");
		
		writeFileMenuItem = new CheckboxMenuItem("Write PNGs");
		writeFileMenuItem.addItemListener(this);
		writeFileMenuItem.setState(Env.toFile);
		m.add(writeFileMenuItem);
		
		writeQKFileMenuItem = new CheckboxMenuItem("Write QK files");
		writeQKFileMenuItem.addItemListener(this);
		writeQKFileMenuItem.setState(Env.toQKFile);
		m.add(writeQKFileMenuItem);
		
		writeLOGFileMenuItem = new CheckboxMenuItem("Write Log & PNGs");
		writeLOGFileMenuItem.addItemListener(this);
		writeLOGFileMenuItem.setState(Env.logFiles);
		m.add(writeLOGFileMenuItem);
		
		m.add("--------");
		
		m.add("Load State from QK File");
		
		m.add("--------");
		
		m.add("Save Parameter Settings");
		m.add("Load Parameter Settings");
		
		theMenuBar.add(m);

		// View menu
		m = new Menu( "View" );
		m.addActionListener( this );

		m.add( "Reset view" );
		
		m.add( "Reset view - RotZ90");
		
		m.add( "Reset view - RotY90");
		
		lockViewMenuItem = new CheckboxMenuItem("Lock view");
		lockViewMenuItem.addItemListener(this);
		lockViewMenuItem.setState(Env.toFile);
		m.add(lockViewMenuItem);
		
		m.add("--------");
		
		m.add("Set to view");
		m.add("Save as view");
		
		//m.add( "Set fav view");
		
		//m.add( "Report view");



		m.addSeparator( );

		headlightMenuItem = new CheckboxMenuItem( "Headlight on/off" );
		headlightMenuItem.addItemListener( this );
		headlightMenuItem.setState( headlightOnOff );
		m.add( headlightMenuItem );

		theMenuBar.add( m );
		
		// Add a menu to select among options for painting etc
		m = new Menu( "Paint_Options" );
		m.add("---General---");
		
		paintOnMenuItem = new CheckboxMenuItem("Paint On");
		paintOnMenuItem.addItemListener(this);
		paintOnMenuItem.setState(Env.paintOn);
		m.add(paintOnMenuItem);
		
		rotateMenuItem = new CheckboxMenuItem("Rotate");
		rotateMenuItem.addItemListener(this);
		rotateMenuItem.setState(Env.viewRotation);
		m.add(rotateMenuItem);
		
		m.add("---Text Info---");
		
		info2DMenuItem = new CheckboxMenuItem("2D");
		info2DMenuItem.addItemListener(this);
		info2DMenuItem.setState(Env.infoIn2D);
		m.add(info2DMenuItem);
		
		info3DMenuItem = new CheckboxMenuItem("3D");
		info3DMenuItem.addItemListener(this);
		info3DMenuItem.setState(Env.infoIn3D);
		m.add(info3DMenuItem);
		
		m.add("---Bug---");

		appearanceMenu = new CheckboxMenuItem[4];
		appearanceMenu[0] = new CheckboxMenuItem( "Shaded" );
		appearanceMenu[0].addItemListener( this );
		if (Env.bugWiredCoarse) { 
			appearanceMenu[0].setState( false );
		} else {
			appearanceMenu[0].setState( !Env.bugWired );
		}
		m.add( appearanceMenu[0] );

		appearanceMenu[1] = new CheckboxMenuItem( "Wireframe" );
		appearanceMenu[1].addItemListener( this );
		if (Env.bugWiredCoarse) { 
			appearanceMenu[1].setState( false );
		} else {
			appearanceMenu[1].setState( Env.bugWired );
		}
		m.add( appearanceMenu[1] );
		
		appearanceMenu[2] = new CheckboxMenuItem( "Coarse Wireframe" );
		appearanceMenu[2].addItemListener( this );
		appearanceMenu[2].setState( Env.bugWiredCoarse );
		//m.add( appearanceMenu[2] );
		
		appearanceMenu[3] = new CheckboxMenuItem( "Off" );
		appearanceMenu[3].addItemListener( this );
		appearanceMenu[3].setState( Env.showCrucible.isActive() );
		m.add( appearanceMenu[3] );
		
		m.add("---Filaments---");
		
		helixFilsMenuItem = new CheckboxMenuItem("Wire Helix");
		helixFilsMenuItem.addItemListener(this);
		helixFilsMenuItem.setState(!Env.helixSpheres);
		//m.add(helixFilsMenuItem);
		
		sphereFilsMenuItem = new CheckboxMenuItem("Spherical Monomers");
		sphereFilsMenuItem.addItemListener(this);
		sphereFilsMenuItem.setState(Env.helixSpheres);
		//m.add(sphereFilsMenuItem);
		
		polarityArrowsMenuItem = new CheckboxMenuItem("Polarity Arrows");
		polarityArrowsMenuItem.addItemListener(this);
		polarityArrowsMenuItem.setState(Env.polarityArrows);
		m.add(polarityArrowsMenuItem);
		
		m.add("---Cross-links---");
		
		showXLinksMenuItem = new CheckboxMenuItem("Show Cross-links");
		showXLinksMenuItem.addItemListener(this);
		showXLinksMenuItem.setState(Env.showXLinks.isActive());
		m.add(showXLinksMenuItem);
		
		showActAsMenuItem = new CheckboxMenuItem("Show ActAs");
		showActAsMenuItem.addItemListener(this);
		showActAsMenuItem.setState(Env.showActAs.isActive());
		m.add(showActAsMenuItem);
		
		theMenuBar.add( m );
		
		m = new Menu ( "Panels" );
		m.addActionListener( this );
		
		m.add("Control Panel");
		m.add("Render from QK files");
		
		theMenuBar.add( m );
		
		m = new Menu ( "Testing" );
		
		releaseNodeMyosMenuItem = new CheckboxMenuItem("Release All Node Myosins/Myosin Dimers");
		releaseNodeMyosMenuItem.addItemListener(this);
		releaseNodeMyosMenuItem.setState(false);
		m.add(releaseNodeMyosMenuItem);
		
		randomNodesOnMenuItem = new CheckboxMenuItem("Remove Random Node and Its Stuff");
		randomNodesOnMenuItem.addItemListener(this);
		randomNodesOnMenuItem.setState(false);
		m.add(randomNodesOnMenuItem);
		
		bugMotionOffMenuItem = new CheckboxMenuItem("Bug Brownian Motion Off");
		bugMotionOffMenuItem.addItemListener(this);
		bugMotionOffMenuItem.setState(Env.bugBrownianMotionOff);
		m.add(bugMotionOffMenuItem);
		
		filMotionOffMenuItem = new CheckboxMenuItem("Filament Brownian Motion Off");
		filMotionOffMenuItem.addItemListener(this);
		filMotionOffMenuItem.setState(Env.brownianFilMotionOff);
		m.add(filMotionOffMenuItem);
		
		myosOffMenuItem = new CheckboxMenuItem("Myosins Completely Off");
		myosOffMenuItem.addItemListener(this);
		myosOffMenuItem.setState(Env.myosinsOff);
		m.add(myosOffMenuItem);
		
		myoMotionOffMenuItem = new CheckboxMenuItem("Myosin Brownian Motion Off");
		myoMotionOffMenuItem.addItemListener(this);
		myoMotionOffMenuItem.setState(Env.brownianMyoMotionOff);
		m.add(myoMotionOffMenuItem);
		
		myoMiniFilMotionOffMenuItem = new CheckboxMenuItem("Myosin Minifil Rod Brownian Motion Off");
		myoMiniFilMotionOffMenuItem.addItemListener(this);
		myoMiniFilMotionOffMenuItem.setState(Env.myoMiniFilBrownianMotionOff);
		m.add(myoMiniFilMotionOffMenuItem);
		
		plasmidMotionOffMenuItem = new CheckboxMenuItem("Plasmid Brownian Motion Off");
		plasmidMotionOffMenuItem.addItemListener(this);
		plasmidMotionOffMenuItem.setState(Env.brownianFilMotionOff);
		m.add(plasmidMotionOffMenuItem);
		
		twoNodesOneFilMenuItem = new CheckboxMenuItem("Two Nodes One Fil");
		twoNodesOneFilMenuItem.addItemListener(this);
		twoNodesOneFilMenuItem.setState(Env.twoNodesOneFil);
		m.add(twoNodesOneFilMenuItem);
		
		actinWithMyoMinisMenuItem = new CheckboxMenuItem("Actin and Myosin Minifilaments");
		actinWithMyoMinisMenuItem.addItemListener(this);
		actinWithMyoMinisMenuItem.setState(Env.actinAndMyoMinis);
		m.add(actinWithMyoMinisMenuItem);
		
		twoByTwoNodesMenuItem = new CheckboxMenuItem("2x2 Nodes");
		twoByTwoNodesMenuItem.addItemListener(this);
		twoByTwoNodesMenuItem.setState(Env.twoByTwoNodes);
		m.add(twoByTwoNodesMenuItem);
		
		threeByThreeNodesMenuItem = new CheckboxMenuItem("3x3 Nodes");
		threeByThreeNodesMenuItem.addItemListener(this);
		threeByThreeNodesMenuItem.setState(Env.threeByThreeNodes.isActive());
		m.add(threeByThreeNodesMenuItem);
		
		nodeChainMenuItem = new CheckboxMenuItem("Node Chain");
		nodeChainMenuItem.addItemListener(this);
		nodeChainMenuItem.setState(Env.nodeChain);
		m.add(nodeChainMenuItem);
		
		compressionCritMenuItem = new CheckboxMenuItem("Compression doesn't affect polymerization");
		compressionCritMenuItem.addItemListener(this);
		compressionCritMenuItem.setState(Env.compressionCritOff);
		//m.add(compressionCritMenuItem);
		
		orderedCenteredMenuItem = new CheckboxMenuItem("Ordered Filaments Are Centered");
		orderedCenteredMenuItem.addItemListener(this);
		orderedCenteredMenuItem.setState(Env.orderedCentered);
		//m.add(orderedCenteredMenuItem);
		
		filCoordSysOnMenuItem = new CheckboxMenuItem("Render Coord. Sys. on Segments");
		filCoordSysOnMenuItem.addItemListener(this);
		filCoordSysOnMenuItem.setState(Env.filCoordSysOn);
		m.add(filCoordSysOnMenuItem);
		
		fixedMyoClustersMenuItem = new CheckboxMenuItem("Fixed Myosin Clusters Test");
		fixedMyoClustersMenuItem.addItemListener(this);
		fixedMyoClustersMenuItem.setState(Env.fixedMyosinClusters.isActive());
		m.add(fixedMyoClustersMenuItem);
		
		glidingAssayMenuItem = new CheckboxMenuItem("Filament Gliding Assay");
		glidingAssayMenuItem.addItemListener(this);
		glidingAssayMenuItem.setState(Env.glidingAssay.isActive());
		m.add(glidingAssayMenuItem);
		
		xLinkTestMenuItem = new CheckboxMenuItem("Test Branched Filament");
		xLinkTestMenuItem.addItemListener(this);
		xLinkTestMenuItem.setState(Env.xLinkTesting);
		m.add(xLinkTestMenuItem);
		
		nodeLinkTestMenuItem = new CheckboxMenuItem("Test Node Linking");
		nodeLinkTestMenuItem.addItemListener(this);
		nodeLinkTestMenuItem.setState(Env.nodeLinkTesting);
		m.add(nodeLinkTestMenuItem);
		
		theMenuBar.add(m);
		
		//theMenuBar.add(infoCCD.Info.infoMenu);

		
	}
	
	public void actionPerformed( ActionEvent event ) {
		String arg = event.getActionCommand( );
		
		if ( arg.equals( "Reset view" ) ) {
			resetView();
		}
		
		if ( arg.equals( "Reset view - RotZ90") ) {
			setRotZ90View();
		}
		
		if ( arg.equals( "Reset view - RotY90") ) {
			setRotY90View();
		}
		
		if ( arg.equals( "Set fav view") ) { setFavoriteView(); }
		
		if ( arg.equals( "Report view") ) { reportView(); }
		
		if ( arg.equals("Set to view")) { setView(null); }
		
		if ( arg.equals("Save as view")) { saveAsView(); }
		

		if (arg.equals("Restart")) { 
			BoxOfActin.setPaused();
			BoxOfActin.restartRun(false); 
		}
		
		if ( arg.equals( "Control Panel" ) ) {
			controlFrame.setVisible(true);
		}
		
		if ( arg.equals( "Render from QK files" ) ) {
			renderControl.setVisible(true);
		}
		
		if ( arg.equals("Load State from QK File") ) {
			prepForQKStateLoad();
			if (Env.fromQKStatePath != null) {
				FileOps.loadQuickState(Env.fromQKStatePath);
			}
		}
		
		if ( arg.equals("Save Parameter Settings") ) {
			saveParamsToFile();
		}
		
		if ( arg.equals("Load Parameter Settings") ) {
			loadParamsFromFile();
		}
	}


	//
	//  Handle checkboxes
	//
	public void itemStateChanged( ItemEvent event )
	{
		Object src = event.getSource( );

		boolean state;
		if ( src == headlightMenuItem )
		{
			state = headlightMenuItem.getState( );
			headlight.setEnable( state );
		}
		
		if ( src == info2DMenuItem) {
			Env.infoIn2D = info2DMenuItem.getState();
		}
		
		if ( src == info3DMenuItem) {
			theCanvas.stopRenderer();
			Env.infoIn3D = info3DMenuItem.getState();
			Thing.theBox.updateTextInfoState();
			try { Thread.sleep(20); } catch (InterruptedException e) { talkln ("error sleeping"); }
			theCanvas.startRenderer();
		}
		
		// Check if it is an appearance choice
		if ( src == appearanceMenu[0] ) {
			appearanceMenu[0].setState( true );
			appearanceMenu[1].setState( false );
			appearanceMenu[2].setState( false );
			appearanceMenu[3].setState( false );
			Env.bugWired = false;
			Env.bugWiredCoarse = false;
			Env.showCrucible.setActive(false);
			Thing.theBox.appearanceChange();
			return;
		}
		if ( src == appearanceMenu[1] ) {
			appearanceMenu[0].setState( false );
			appearanceMenu[1].setState( true );
			appearanceMenu[2].setState( false );
			appearanceMenu[3].setState( false );
			Env.bugWired = true;
			Env.bugWiredCoarse = false;
			Env.showCrucible.setActive(false);
			Thing.theBox.appearanceChange();
			return;
		}
		
		if ( src == appearanceMenu[2] ) {
			appearanceMenu[0].setState( false );
			appearanceMenu[1].setState( false );
			appearanceMenu[2].setState( true );
			appearanceMenu[3].setState( false );
			Env.bugWired = true;
			Env.bugWiredCoarse = true;
			Env.showCrucible.setActive(false);
			Thing.theBox.appearanceChange();
			return;
		}
		
		if ( src == appearanceMenu[3] ) {
			appearanceMenu[0].setState( false );
			appearanceMenu[1].setState( false );
			appearanceMenu[2].setState( false );
			appearanceMenu[3].setState( true );
			Env.showCrucible.setActive(true);
			Thing.theBox.appearanceChange();
			return;
		}
		
		if ( src == paintOnMenuItem) {
			Env.paintOn = paintOnMenuItem.getState();
		}
		
		if (src == writeFileMenuItem) {
			if (writeFileMenuItem.getState()) { 
				prepForJPEGWriting(); 
			} else {
				Env.toFile = false;
				Env.toFilePath = null;
				Env.toFileName = null;
			}
		}
		
		if (src == writeQKFileMenuItem) {
			if (writeQKFileMenuItem.getState()) { 
				prepForQKWriting(); 
			} else {
				Env.toQKFile = false;
				Env.qkFilePath = null;
				Env.outFileName = null;
			}
		}
		
		if (src == writeLOGFileMenuItem) {
			if (writeLOGFileMenuItem.getState()) { 
				prepForAllOutWriting(); 
			} else {
				Env.fullSetToFile = false;
				Env.parentFolderPath = null;
				// turn off PNG Writing
				Env.toFile = false;
				writeFileMenuItem.setState(false);
				writeFileMenuItem.setEnabled(true);
				theCanvas.capturePath = null;
				theCanvas.captureName = null;
				// turn off LOG Writing
				Env.logFiles = false;
				writeLOGFileMenuItem.setState(false);
				writeLOGFileMenuItem.setEnabled(true);
				FileOps.outFileMade = false;
			}
		}
		
		if (src == pausedMenuItem) {
			BoxOfActin.setPaused();	
		}
		
		if (src == runMenuItem) {
			BoxOfActin.setRunning();
		}
		
		if (src == lockViewMenuItem) {
			lockView();
			Env.lockView.setActive(lockViewMenuItem.getState());
		}
		
		if (src == rotateMenuItem) {
			Env.viewRotation = rotateMenuItem.getState();
		}
		
		if (src == sphereFilsMenuItem) {
			if (sphereFilsMenuItem.getState() != Env.helixSpheres) {
				Env.helixSpheres = sphereFilsMenuItem.getState();
				helixFilsMenuItem.setState(!Env.helixSpheres);
				FilSegment.resetGraphicsAll();
			}
		}
		
		if (src == helixFilsMenuItem) {
			if (Env.helixSpheres != !helixFilsMenuItem.getState()) {
				Env.helixSpheres = !helixFilsMenuItem.getState();
				sphereFilsMenuItem.setState(Env.helixSpheres);
				FilSegment.resetGraphicsAll();
			}
		}
		 
		if (src == polarityArrowsMenuItem) {
			Env.polarityArrows = polarityArrowsMenuItem.getState();
			FilSegment.resetGraphicsAll();
		}
		
		if (src == showXLinksMenuItem) {
			Env.showXLinks.setActive(showXLinksMenuItem.getState()); 
		}
		
		if (src == showActAsMenuItem) {
			Env.showActAs.setActive(showActAsMenuItem.getState()); 
		}
		
		if (src == filMotionOffMenuItem) {
			Env.brownianFilMotionOff = filMotionOffMenuItem.getState();
		}
		
		if (src == bugMotionOffMenuItem) {
			Env.bugBrownianMotionOff = bugMotionOffMenuItem.getState();
		}
		
		if (src == releaseNodeMyosMenuItem) {
			ProteinNode.releaseAllMyos();
		}
		
		if (src == randomNodesOnMenuItem) {  // trigger one random motor removal for testing
			Env.randomNodesOn = randomNodesOnMenuItem.getState();
		}
		
		if (src == myosOffMenuItem) {
			Env.myosinsOff = myosOffMenuItem.getState();
		}
		
		if (src == myoMotionOffMenuItem) {
			Env.brownianMyoMotionOff = myoMotionOffMenuItem.getState();
		}
		
		if (src == myoMiniFilMotionOffMenuItem) {
			Env.myoMiniFilBrownianMotionOff = myoMiniFilMotionOffMenuItem.getState();
		}
		
		if (src == plasmidMotionOffMenuItem) {
			Env.nodeBrownianMotionOff = plasmidMotionOffMenuItem.getState();
		}
		
		if (src == compressionCritMenuItem) {
			Env.compressionCritOff = compressionCritMenuItem.getState();
		}
		
		if (src == twoNodesOneFilMenuItem) {
			Env.twoNodesOneFil = twoNodesOneFilMenuItem.getState();
			Env.showCrucible.setActive(twoNodesOneFilMenuItem.getState());
			appearanceMenu[0].setState( false );
			appearanceMenu[1].setState( false );
			appearanceMenu[2].setState( false );
			appearanceMenu[3].setState( true );
			BoxOfActin.restartRun(false);
		}
		
		if (src == actinWithMyoMinisMenuItem) {
			Env.actinAndMyoMinis = actinWithMyoMinisMenuItem.getState();
			Env.showCrucible.setActive(actinWithMyoMinisMenuItem.getState());
			appearanceMenu[0].setState( false );
			appearanceMenu[1].setState( false );
			appearanceMenu[2].setState( false );
			appearanceMenu[3].setState( true );
			BoxOfActin.restartRun(false);
		}
		
		if (src == twoByTwoNodesMenuItem) {
			Env.twoByTwoNodes = twoByTwoNodesMenuItem.getState();
			//Env.bugOff.setActive(fourNodesMenuItem.getState());
			//appearanceMenu[0].setState( false );
			//appearanceMenu[1].setState( false );
			//appearanceMenu[2].setState( false );
			//appearanceMenu[3].setState( true );
			BoxOfActin.restartRun(false);
		}
		
		if (src == threeByThreeNodesMenuItem) {
			Env.threeByThreeNodes.setActive(threeByThreeNodesMenuItem.getState());
			//Env.bugOff.setActive(fourNodesMenuItem.getState());
			//appearanceMenu[0].setState( false );
			//appearanceMenu[1].setState( false );
			//appearanceMenu[2].setState( false );
			//appearanceMenu[3].setState( true );
			BoxOfActin.restartRun(false);
		}
		
		if (src == nodeChainMenuItem) {
			Env.nodeChain = nodeChainMenuItem.getState();
			//Env.bugOff.setActive(nodeChainMenuItem.getState());
			//appearanceMenu[0].setState( false );
			//appearanceMenu[1].setState( false );
			//appearanceMenu[2].setState( false );
			//appearanceMenu[3].setState( true );
			BoxOfActin.restartRun(false);
		}
		
		if (src == filCoordSysOnMenuItem) {
			Env.filCoordSysOn = filCoordSysOnMenuItem.getState();
		}
		
		if (src == fixedMyoClustersMenuItem) {
			Env.fixedMyosinClusters.setActive(fixedMyoClustersMenuItem.getState());
			BoxOfActin.restartRun(false);
		}
		
		if (src == glidingAssayMenuItem) {
			Env.glidingAssay.setActive(glidingAssayMenuItem.getState());
			BoxOfActin.restartRun(false);
		}
		
		if (src == xLinkTestMenuItem) {
			Env.xLinkTesting = xLinkTestMenuItem.getState();
			BoxOfActin.restartRun(false);
		}
		
		if (src == nodeLinkTestMenuItem) {
			Env.nodeLinkTesting = nodeLinkTestMenuItem.getState();
			BoxOfActin.restartRun(false);
		}
		

	}
	
	
	public static void allBranchGroupCapabilities(BranchGroup bg) {
		bg.setCapability(BranchGroup.ALLOW_CHILDREN_EXTEND);
		bg.setCapability(BranchGroup.ALLOW_CHILDREN_WRITE);
		bg.setCapability(BranchGroup.ALLOW_CHILDREN_READ);
		bg.setCapability(BranchGroup.ALLOW_DETACH);
	}
	
	private static void talkln (String info) {
		System.out.println(info);
	}
	
	private static void talk (String info) {
		System.out.println(info);
	}
	
	public void loadParamsFromFile () {
		String loadFromName = null;
		loadFromName = FileOps.getFileToLoad("Load parameter settings from ...", this);
		if (loadFromName == null) {
			talkln ("Parameter settings not loaded.");
		} else {
			File loadFromFile = new File(loadFromName);
			FileOps.loadParamConfig(loadFromFile, true);
		}
	}
	
	public void saveParamsToFile () {
		String saveToName = null;
		saveToName = FileOps.getFileToSave("Save parameter settings to ...", this);
		if (saveToName == null) {
			talkln ("Parameter settings not saved.");
		} else {
			File saveToFile = new File(saveToName);
			FileOps.writeParamConfig(saveToFile);
		}
	}
	
	public static void prepForAllOutWriting () {
		Env.parentFolderPath = null;
		Env.parentFolderPath = FileOps.getDirectoryToSave("Create Parent Folder For Node-Links, PNGs, Log etc...", null);
		if (Env.parentFolderPath == null) { 
			Env.fullSetToFile = false;
			writeFileMenuItem.setState(false);
		} else {
			theFrame.setTitle( Env.parentFolderPath );
			Env.fullSetToFile = true;
			// set for PNG writing
			Env.toFile = true;
			writeFileMenuItem.setState(true);
			writeFileMenuItem.setEnabled(false);
			theCanvas.capturePath = Env.parentFolderPath + "/pngs";
			theCanvas.captureName = "img";
			File pngFolder = new File(theCanvas.capturePath);
			pngFolder.mkdir();
			// set for LOG writing
			Env.logFiles = true;
			writeLOGFileMenuItem.setState(true);
			writeLOGFileMenuItem.setEnabled(false);
			FileOps.mkOutFile(Env.parentFolderPath + "/log.csv");
			FileOps.writeToOutFile();
			// setup for JSon writing
			FileOps.setupJSons();
			// save current parameter into parent folder
			File saveToFile = new File(Env.parentFolderPath + "/params");
			FileOps.writeParamConfig(saveToFile);
		}
	}
	
	public void prepForJPEGWriting () {
		Env.toFilePath = null;
		Env.toFilePath = FileOps.getDirectoryToSave("Create folder for PNG files ...", this);
		if (Env.toFilePath == null) { 
			Env.toFile = false;
			writeFileMenuItem.setState(false);
		} else {
			int lastbitIndex = Env.toFilePath.lastIndexOf(File.separator);
			Env.toFileName = Env.toFilePath.substring(lastbitIndex+1);
			theCanvas.capturePath = Env.toFilePath;
			theCanvas.captureName = Env.toFileName;
			Env.toFile = true;
		}
	}
	
	public static void prepForQKWriting () {
		Env.qkFilePath = null;
		Env.qkFilePath = FileOps.getDirectoryToSave("Create folder for QK files ...", null);
		if (Env.qkFilePath == null) { 
			Env.toQKFile = false;
			writeQKFileMenuItem.setState(false);
		} else {
			int lastbitIndex = Env.qkFilePath.lastIndexOf(File.separator);
			Env.outFileName = Env.qkFilePath.substring(lastbitIndex+1);
			Env.toQKFile = true;
		}
	}
	
	public static void prepForQKRender () {
		Env.fromQKFilePath = null;
		Env.fromQKFilePath = FileOps.getDirectoryToLoad("Select folder of QK files to render ...", null);
		if (Env.fromQKFilePath == null) { 
			Env.fromQKFileName = null;
			Env.fromQKFile = false;
		} else {
			int lastbitIndex = Env.fromQKFilePath.lastIndexOf(File.separator);
			Env.fromQKFileName = Env.fromQKFilePath.substring(lastbitIndex+1);
			Env.fromQKFile = true;
		}
	}
	
	public static void prepForQKStateLoad () {
		Env.fromQKStatePath = null;
		Env.fromQKStatePath = FileOps.getFileToLoad("Select QK file state to load ...", null);
	}


	//--------------------------------------------------------------
	//  USER INTERFACE
	//--------------------------------------------------------------


	/**
	 *  Shows the application's frame, making it and its menubar,
	 *  3D canvas, and 3D content visible.
	 */
	public void showFrame( )
	{
		theFrame.setVisible(true);
	}


	/**
	 *  Quits the application.
	 */
	public void quit( )
	{
		System.exit( 0 );
	}



	/**
	 *  Handles checkbox items on a CheckboxMenu.  The Example class has
	 *  none of its own, but subclasses may have some.
	 *
	 *  @param   menu    which CheckboxMenu needs action
	 *  @param   check   which CheckboxMenu item has changed
	 */
	public void checkboxChanged( CheckboxMenu menu, int check )
	{
		// None for us
	}


	/**
	 *  Handles a window closing event notifying the application
	 *  that the user has chosen to close the application without
	 *  selecting the "Exit" menu item.
	 *
	 *  @param   event   a WindowEvent indicating the window is closing
	 */
	public void windowClosing( WindowEvent event )
	{
		quit( );
	}
	public void windowClosed( WindowEvent event )
	{
	}
	public void windowOpened( WindowEvent event )
	{
	}
	public void windowIconified( WindowEvent event )
	{
	}
	public void windowDeiconified( WindowEvent event )
	{
	}
	public void windowActivated( WindowEvent event )
	{
	}
	public void windowDeactivated( WindowEvent event )
	{
	}

	public void componentHidden(ComponentEvent e) {
		// TODO Auto-generated method stub
		
	}

	public void componentMoved(ComponentEvent e) {
		// TODO Auto-generated method stub
		
	}

	public void componentResized(ComponentEvent e) {
		updateFrameSizeParams();
		
	}

	public void componentShown(ComponentEvent e) {
		// TODO Auto-generated method stub
		
	}



}