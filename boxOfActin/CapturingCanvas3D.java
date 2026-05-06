package boxOfActin;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.*;

import javax.imageio.ImageIO;
import javax.media.j3d.*;
import javax.vecmath.*;

import com.sun.image.codec.jpeg.*;
import java.text.*;

/** Class CapturingCanvas3D, using the instructions from the Java3D 
    FAQ pages on how to capture a still image in jpeg format.
    * switched to PNGs --jba *

    A capture button would call a method that looks like


    public static void captureImage(CapturingCanvas3D MyCanvas3D) {
	MyCanvas3D.writeJPEG_ = true;
	MyCanvas3D.repaint();
    }


    Peter Z. Kunszt
    Johns Hopkins University
    Dept of Physics and Astronomy
    Baltimore MD
*/

public class CapturingCanvas3D extends Canvas3D  {
	public boolean rendering = false;
    public boolean writePNG_ = false;
    public boolean useAltCaptureName = false;
    public String captureName;
    public String capturePath;
    public String altCaptureName;
    public String fileName;
    public static int drawingsMade = 0;
    private int postSwapCount_;
    DecimalFormat countFormat = new DecimalFormat ("#0000.#;#0000.#");
    J3DGraphics2D g2D;

    public CapturingCanvas3D(GraphicsConfiguration gc) {
    		super(gc);
    		postSwapCount_ = 0;
    		g2D = this.getGraphics2D();
    }
    
    public void preRender() {

    		rendering = true;
    }
    
    public void do2DDrawing() {
    	if (true) { //(Env.paintOn) {
    		rendering = true;
    		//if ((writeJPEG_) & (Env.timeStampJPEGs)) {
    			g2D.setColor(Env.controlForeColor);
    			g2D.setFont(Env.text2DFont);
    			String showString = "";
    			String sepString = "   ";
    			if (Env.showTime.isActive()) { showString += BoxOfActin_Graphics.getTimeString() + sepString; } else { showString += " ";} 
    			if (Env.showConc.isActive()) { showString += BoxOfActin_Graphics.getConcString() + sepString; }
    			if (Env.showNonHydroConc.isActive()) { showString += BoxOfActin_Graphics.getNonHydroConcString() + sepString; }
    			if (Env.showFilCt.isActive()) { showString += BoxOfActin_Graphics.getFilCtString() + sepString; }
    			if (Env.showFilSegCt.isActive()) { showString += BoxOfActin_Graphics.getFilSegCtString() + sepString; }
    			if (Env.showFilLinkCt.isActive()) { showString += BoxOfActin_Graphics.getFilLinkCtString() + sepString; }
    			if (Env.showMonCt.isActive()) { showString += BoxOfActin_Graphics.getMonCtString() + sepString; }
    			if (Env.showActACt.isActive()) { showString += BoxOfActin_Graphics.getActACtString() + sepString; }
    			if (Env.showActABoundCt.isActive()) { showString += BoxOfActin_Graphics.getActABoundCtString() + sepString; }
    			if (Env.showMyoCt.isActive()) { showString += BoxOfActin_Graphics.getMyoCtString() + sepString; }
    			if (Env.showArp23Ct.isActive()) { showString += BoxOfActin_Graphics.getArp23CtString() + sepString; }
    			if (Env.showProteinNodeCt.isActive()) { showString += BoxOfActin_Graphics.getNodeCtString() + sepString; }
    			if (Env.showMyoMiniCt.isActive()) { showString += BoxOfActin_Graphics.getMyoMiniCtString() + sepString; }
    			if (Env.showBugDragScale.isActive()) { showString += BoxOfActin_Graphics.getBugDragString() + sepString; }

    			g2D.drawString(showString,10,20);
    			
    			g2D.flush(false);
    		//}
    	}
    }
    
    public void postRender () {
    	if ((Env.infoIn2D) && (Env.paintOn) ) {
    		g2D = this.getGraphics2D();
    		do2DDrawing(); 
    	}
    	rendering = true;
    }

    public void postSwap() {
	    if (writePNG_) {
	    	rendering = true;
		    int width = this.getWidth();
		    int height = this.getHeight();
		    GraphicsContext3D  ctx = getGraphicsContext3D();
		    // The raster components need all be set!
		    Raster ras = new Raster(
	                   new Point3f(-1.0f,-1.0f,-1.0f),
			   Raster.RASTER_COLOR,
			   0,0,
			   width,height,
			   new ImageComponent2D(
	                             ImageComponent.FORMAT_RGB,
				     new BufferedImage(width,height,
						       BufferedImage.TYPE_INT_RGB)),
			   null);
	
		    ctx.readRaster(ras);
	
		    // Now strip out and PNG the image info
		    BufferedImage img = ras.getImage().getImage();
		    pngFromBufferedImage(img);
		    
		    writePNG_ = false;
			useAltCaptureName = false;
		    postSwapCount_++;
		}
	    	rendering = false;
    }
    
    public void setAltCapture (String altName) {
    		altCaptureName = altName;
    		useAltCaptureName = true;
    }
    
    public boolean isWritingJPEG() {
    		return writePNG_;
    }
    
    public boolean isRendering () {
    		return rendering;
    }
    
    public boolean firstRender () {
    	if (postSwapCount_ == 0) { return true; } else { return false; }
    }
    
    public void resetFileCt () {
    	postSwapCount_ = 0;
    }
    
    public void pngFromBufferedImage(BufferedImage bImg) {
    	String fileName;
    	if (useAltCaptureName) {
    		fileName = capturePath +"/" + altCaptureName + ".png";
    	} else {
    		//fileName = capturePath +"/" + FileOps.makeNameFromIntStep(captureName,Env.counter) + ".png";
    		fileName = capturePath +"/" + FileOps.makeNameFromSimpleCounter(captureName,drawingsMade) + ".png";
    		drawingsMade++;
    	}
    	System.out.print ("Writing " + fileName + " ... ");
	    try {
		  // create file
	      File outFile = new File(fileName);
		  RenderedImage rendImage = (RenderedImage)bImg;
		  ImageIO.write(rendImage, "png", outFile);
	    }
	    catch( Exception e )
	    {
	      e.printStackTrace();
	    }
	    System.out.println("done.");
	 }
    
}
